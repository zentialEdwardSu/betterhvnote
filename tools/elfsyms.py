"""Minimal ELF64 dynamic-symbol dumper (no external deps).

Usage: python elfsyms.py <lib.so> [filter-substring]
Prints exported (defined, global/weak) symbol names.
"""
import struct
import sys


def read_elf_symbols(path):
    with open(path, "rb") as fh:
        data = fh.read()
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise SystemExit(f"{path}: not an ELF64 file")

    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = \
            struct.unpack_from("<IIQQQQIIQQ", data, off)
        sections.append(dict(name=name, type=stype, offset=offset, size=size,
                             link=link, entsize=entsize))

    shstr = sections[e_shstrndx]

    def sec_name(sh):
        base = shstr["offset"] + sh["name"]
        end = data.index(b"\x00", base)
        return data[base:end].decode("utf-8", "replace")

    out = []
    for sh in sections:
        # 11 = SHT_DYNSYM, 2 = SHT_SYMTAB
        if sh["type"] not in (11, 2) or not sh["entsize"]:
            continue
        strtab = sections[sh["link"]]
        count = sh["size"] // sh["entsize"]
        for j in range(count):
            off = sh["offset"] + j * sh["entsize"]
            st_name, st_info, st_other, st_shndx, st_value, st_size = \
                struct.unpack_from("<IBBHQQ", data, off)
            if st_name == 0 or st_shndx == 0:  # unnamed or undefined
                continue
            base = strtab["offset"] + st_name
            end = data.index(b"\x00", base)
            out.append(data[base:end].decode("utf-8", "replace"))
    return sorted(set(out))


if __name__ == "__main__":
    lib = sys.argv[1]
    needle = sys.argv[2] if len(sys.argv) > 2 else ""
    names = [n for n in read_elf_symbols(lib) if needle in n]
    print(f"# {lib}: {len(names)} matching exported symbols")
    for n in names:
        print(n)
