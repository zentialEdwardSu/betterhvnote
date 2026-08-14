// JNI bridge over Google's ink-stroke-modeler (ink::stroke_model::StrokeModeler).
//
// Symbol names are the standard Java_<package>_<Class>_<method> mangling for
// com.betterhv.note.jni.InkStrokeModelerJNI -- no JNI_OnLoad / dynamic
// registration, so the Kotlin class package + name MUST stay exactly that (the
// same hard constraint the three vendor JNI wrappers already document). Verify
// with `python tools/elfsyms.py <lib> nativeReset` after building.
//
// Design (see plan doc §2):
//  * One process-wide StrokeModeler, (re)initialized per gesture via nativeReset.
//    All calls come from PenDrawView's single Handler thread; the mutex only
//    guards against a pathological concurrent first-init, it is not on a hot
//    multi-threaded path.
//  * Batch-oriented: one nativeUpdate call marshals a whole batch of samples as
//    a flat [x,y,pressure, ...] FloatArray, rather than one JNI crossing per
//    point.
//  * Synthetic monotonic time: the ROM stamps every sample in a batch with the
//    same receipt time, which StrokeModeler cannot use (it requires strictly
//    non-decreasing time and rejects identical inputs). We advance a nominal
//    fixed timestep per sample instead. A linear spring-damper is scale-
//    invariant in space, so pixel units reuse the library's second-tuned
//    defaults without rescaling; only the time unit has to be consistent.

#include <jni.h>

#include <mutex>
#include <vector>

#include "ink_stroke_modeler/params.h"
#include "ink_stroke_modeler/stroke_modeler.h"
#include "ink_stroke_modeler/types.h"

namespace {

using ::ink::stroke_model::Input;
using ::ink::stroke_model::Result;
using ::ink::stroke_model::StrokeEndPredictorParams;
using ::ink::stroke_model::StrokeModeler;
using ::ink::stroke_model::StrokeModelParams;
using ::ink::stroke_model::Time;

// The ROM does not report per-sample timing, so we synthesize a monotonic
// clock at this nominal rate. Kept in sync with OneEuroSmoother's own nominal
// 120 Hz assumption so the two smoothers behave comparably; tune on-device.
constexpr double kNominalSampleHz = 120.0;
constexpr double kNominalDt = 1.0 / kNominalSampleHz;

// Return codes shared with the Kotlin side. Non-negative = number of Results
// produced; negative = error (Kotlin falls back to OneEuroSmoother).
constexpr jint kErrNotInit = -1;        // Reset() never succeeded
constexpr jint kErrModelerError = -2;   // Update/Predict returned !ok, or bad args
constexpr jint kErrBufferTooSmall = -3; // caller's output array can't hold Results

std::mutex g_mutex;
StrokeModeler* g_modeler = nullptr;  // owned; created lazily on first reset
double g_next_time = 0.0;            // synthetic monotonic time, seconds
double g_saved_time = 0.0;           // checkpoint for Save/Restore
bool g_save_active = false;

// Defaults tuned for pixel/second units. Wobble smoothing is left OFF: its
// speed_floor/speed_ceiling are in px/s and need real-hardware tuning (deferred
// to the on-device milestone); disabling keeps params validation trivially
// satisfied while the spring-mass position model and pressure interpolation --
// the parts that address the live/final consistency goal -- do the work.
StrokeModelParams DefaultParams() {
  StrokeModelParams p;
  p.wobble_smoother_params.is_enabled = false;
  // Position modeler keeps the library defaults (spring_mass_constant, drag),
  // which are >0 and were tuned against a seconds time base -- matching ours.
  p.sampling_params.min_output_rate = kNominalSampleHz;
  p.sampling_params.end_of_stroke_stopping_distance = 0.01f;  // px, << sample spacing
  p.sampling_params.end_of_stroke_max_iterations = 20;
  p.prediction_params = StrokeEndPredictorParams{};
  return p;
}

// Writes results as [x, y, pressure] triples into outXYP. Returns the count, or
// kErrBufferTooSmall if the array can't hold them.
jint MarshalResults(JNIEnv* env, const std::vector<Result>& results,
                    jfloatArray outXYP) {
  const jint count = static_cast<jint>(results.size());
  const jsize cap = env->GetArrayLength(outXYP);
  if (static_cast<jsize>(count) * 3 > cap) return kErrBufferTooSmall;
  if (count == 0) return 0;

  std::vector<jfloat> buf(static_cast<size_t>(count) * 3);
  for (jint i = 0; i < count; ++i) {
    buf[i * 3] = results[i].position.x;
    buf[i * 3 + 1] = results[i].position.y;
    buf[i * 3 + 2] = results[i].pressure;
  }
  env->SetFloatArrayRegion(outXYP, 0, count * 3, buf.data());
  return count;
}

}  // namespace

extern "C" {

// (Re)initialize the modeler for a new gesture. Resets the synthetic clock.
// Returns 0 on success, kErrModelerError if the params fail validation.
JNIEXPORT jint JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativeReset(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) g_modeler = new StrokeModeler();
  g_next_time = 0.0;
  g_saved_time = 0.0;
  g_save_active = false;
  return g_modeler->Reset(DefaultParams()).ok() ? 0 : kErrModelerError;
}

// Feed one batch. inputXYP is [x,y,pressure, ...] (length >= 3*N); eventTypes
// is N bytes (0=DOWN, 2=UP, anything else=MOVE). The first sample of a gesture
// must be DOWN. Modeled Results are written to outXYP as [x,y,pressure] triples;
// returns the Result count (>=0) or a negative error code.
JNIEXPORT jint JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativeUpdate(
    JNIEnv* env, jobject, jfloatArray inputXYP, jbyteArray eventTypes,
    jfloatArray outXYP) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) return kErrNotInit;

  const jint n = env->GetArrayLength(eventTypes);
  if (n <= 0) return 0;
  if (env->GetArrayLength(inputXYP) < n * 3) return kErrModelerError;

  std::vector<jfloat> in(static_cast<size_t>(n) * 3);
  env->GetFloatArrayRegion(inputXYP, 0, n * 3, in.data());
  std::vector<jbyte> events(static_cast<size_t>(n));
  env->GetByteArrayRegion(eventTypes, 0, n, events.data());

  std::vector<Result> results;
  for (jint i = 0; i < n; ++i) {
    Input input;
    switch (events[i]) {
      case 0:
        input.event_type = Input::EventType::kDown;
        break;
      case 2:
        input.event_type = Input::EventType::kUp;
        break;
      default:
        input.event_type = Input::EventType::kMove;
        break;
    }
    input.position = {in[i * 3], in[i * 3 + 1]};
    input.pressure = in[i * 3 + 2];
    input.time = Time(g_next_time);
    g_next_time += kNominalDt;
    if (!g_modeler->Update(input, results).ok()) return kErrModelerError;
  }
  return MarshalResults(env, results, outXYP);
}

// Speculative extension of the in-progress stroke, for a live tail that leads
// the lagging modeled position. Does not consume input or advance the clock
// (StrokeModeler::Predict is const). Returns Result count or a negative error
// (e.g. kErrModelerError when no stroke is in progress).
JNIEXPORT jint JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativePredict(
    JNIEnv* env, jobject, jfloatArray outXYP) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) return kErrNotInit;
  std::vector<Result> results;
  if (!g_modeler->Predict(results).ok()) return kErrModelerError;
  return MarshalResults(env, results, outXYP);
}

// Checkpoint the modeler (and the synthetic clock) so a later Restore can undo
// intervening Updates. Provided for completeness; the live-tail path uses the
// const Predict and does not require it.
JNIEXPORT void JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativeSave(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) return;
  g_modeler->Save();
  g_saved_time = g_next_time;
  g_save_active = true;
}

JNIEXPORT void JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativeRestore(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) return;
  g_modeler->Restore();
  if (g_save_active) g_next_time = g_saved_time;
}

// Clear the in-progress stroke but keep the model parameters, so the next
// gesture can start without re-validating params.
JNIEXPORT void JNICALL
Java_com_betterhv_note_jni_InkStrokeModelerJNI_nativeClear(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_modeler == nullptr) return;
  // no-arg Reset() clears the stroke, retains params; only errors if the model
  // was never initialized, which our nativeReset-first sequence rules out.
  // Explicitly discard the [[nodiscard]] Status -- nothing actionable here.
  (void)g_modeler->Reset();
  g_next_time = 0.0;
  g_saved_time = 0.0;
  g_save_active = false;
}

}  // extern "C"
