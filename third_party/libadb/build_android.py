#!/usr/bin/env python3
"""Build the three libadb prebuilts from verified upstream source, without changing APK inputs."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shlex
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path, identity):
    if path.stat().st_size != identity["bytes"] or sha256(path) != identity["sha256"]:
        raise ValueError(f"Size/SHA-256 mismatch: {path.name}")


def revision(path):
    for line in path.read_text().splitlines():
        if line.strip().startswith("Pkg.Revision"):
            return line.split("=", 1)[1].strip()
    raise ValueError(f"Missing SDK revision: {path}")


def prepare_archive(identity, cache):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (identity["sha256"] + ".zip")
    if archive.exists():
        if archive.is_symlink():
            raise ValueError("Archive cache must not be a symlink")
        verify(archive, identity)
        return archive
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache, suffix=".download", delete=False) as out:
            temporary = Path(out.name)
            with urllib.request.urlopen(identity["url"], timeout=120) as response:
                count = 0
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if count > identity["bytes"]:
                        raise ValueError("Source archive exceeds locked byte size")
                    out.write(chunk)
        verify(temporary, identity)
        temporary.replace(archive)
        return archive
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def extract_verified_source(archive, identity, output):
    verify(archive, identity)
    if output.is_symlink():
        raise ValueError("Source cache must not be a symlink")
    output.mkdir(parents=True, exist_ok=True)
    output = output.resolve()
    root = output
    expected = set()
    prefix = identity["archive_root"] + "/"
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        # Validate the complete archive before writing any extracted file.
        for entry in entries:
            if not entry.filename.startswith(prefix) or "\\" in entry.filename:
                raise ValueError("Unexpected archive path")
            relative = entry.filename[len(prefix):]
            parts = PurePosixPath(relative).parts
            if ".." in parts or PurePosixPath(relative).is_absolute():
                raise ValueError("Unsafe archive path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Source archive symlinks are not allowed")
            target = output / relative
            if (target.is_symlink() or not target.resolve().is_relative_to(root) or
                    any(parent.is_symlink() for parent in target.parents if parent != root and root in parent.parents)):
                raise ValueError("Unsafe cached source path")
            if relative and not entry.is_dir():
                if relative in expected:
                    raise ValueError("Duplicate archive file")
                expected.add(relative)
        for entry in entries:
            relative = entry.filename[len(prefix):]
            if not relative or entry.is_dir():
                continue
            target = output / relative
            data = source.read(entry)
            if not target.is_file() or target.read_bytes() != data:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
    extra = {str(path.relative_to(output)) for path in output.rglob("*") if path.is_file() or path.is_symlink()} - expected
    if extra:
        raise ValueError(f"Unexpected cached source files: {sorted(extra)}")
    return output


def run_logged(command, path, env):
    with path.open("w") as output:
        subprocess.run(command, env=env, stdout=output, stderr=subprocess.STDOUT, check=True)


def leading_notice(text, assembly=False, perl=False):
    """Preserve complete leading copyright/license comments without copying source code."""
    remaining = text.lstrip()
    comments = []
    while remaining:
        if remaining.startswith("/*"):
            end = remaining.find("*/")
            if end < 0:
                raise ValueError("Unterminated source notice")
            comments.append(remaining[:end + 2])
            remaining = remaining[end + 2:].lstrip()
        elif (remaining.startswith(("//", "# ", "#\t", "#!")) or remaining.startswith("#\n") or
              (perl and remaining.startswith("#"))):
            line, _, remaining = remaining.partition("\n")
            comments.append(line)
            remaining = remaining.lstrip()
        elif assembly and remaining.startswith(("#include", "#if", "#else", "#endif")):
            # Hand-written assembly may place its attribution after an architecture guard.
            _, _, remaining = remaining.partition("\n")
            remaining = remaining.lstrip()
        else:
            break
    result = "\n".join(comments)
    return result if any(word in result.lower() for word in ("copyright", "license", "public domain")) else ""


def compiled_sources(database, source_root):
    """Resolve both CMake aliases and paths relative to each compile command's directory."""
    source_root = source_root.resolve()
    result = set()
    for entry in json.loads(database.read_text()):
        directory = (database.parent / entry["directory"]).resolve()
        arguments = entry.get("arguments") or shlex.split(entry["command"])
        object_path = (directory / arguments[arguments.index("-o") + 1]).resolve()
        source_path = (directory / entry["file"]).resolve()
        if object_path.is_file() and source_path.is_relative_to(source_root):
            result.add(source_path)
    if not result:
        raise ValueError("No compiled BoringSSL sources found; refusing incomplete notices")
    return result


def perlasm_sources(source_root):
    # This expansion follows the pinned util/pregenerate/build.go, including dst overrides.
    text = "\n".join(line for line in (source_root / "build.json").read_text().splitlines()
                     if not line.lstrip().startswith("//"))
    generated = {}
    for target, settings in json.loads(text).items():
        for key, entries in settings.items():
            if not key.startswith("perlasm_"):
                continue
            platforms = ("linux",) if key == "perlasm_arm" else (
                ("apple", "linux", "win") if key == "perlasm_aarch64" else ("apple", "linux"))
            for entry in entries:
                stem = entry.get("dst", Path(entry["src"]).stem)
                for platform_name in platforms:
                    generated[f"gen/{target}/{stem}-{platform_name}.S"] = entry["src"]
    return generated


def collect_source_notices(database, source_root, dependencies):
    source_root = source_root.resolve()
    compiled = compiled_sources(database, source_root)
    dependency_names = {line[4:] for line in dependencies.splitlines() if line.startswith("    ")}
    included = {(database.parent / name).resolve() for name in dependency_names}
    included = {path for path in included if path.is_relative_to(source_root)}
    if not compiled.issubset(included) or not included - compiled:
        raise ValueError("Missing BoringSSL compiler dependency records; refusing incomplete notices")
    generators = perlasm_sources(source_root)
    notices, generated_inputs, missing_headers = {}, {}, []
    for source_path in sorted(included):
        relative = source_path.relative_to(source_root).as_posix()
        notice = leading_notice(source_path.read_text(), assembly=source_path.suffix == ".S")
        if notice:
            notices[relative] = notice
        if relative in generators:
            generator = generators[relative]
            generator_notice = leading_notice((source_root / generator).read_text(), perl=True)
            if not generator_notice:
                raise ValueError(f"Missing generated assembly source notice: {generator}")
            notices[generator] = generator_notice
            generated_inputs[relative] = generator
        elif not notice:
            if relative.startswith("third_party/fiat/"):
                # These generated files have no notice header; the nested MIT LICENSE applies.
                notices["third_party/fiat/LICENSE"] = (source_root / "third_party/fiat/LICENSE").read_text()
                missing_headers.append(relative)
            else:
                raise ValueError(f"Missing source/dependency notice: {relative}")
    if not notices:
        raise ValueError("No BoringSSL copyright/license notices found")
    evidence = {"compiled_sources": sorted(path.relative_to(source_root).as_posix() for path in compiled),
                "dependency_sources": sorted(path.relative_to(source_root).as_posix() for path in included),
                "generated_assembly_inputs": generated_inputs,
                "notice_sources": sorted(notices), "dependencies_covered_by_fiat_license": missing_headers}
    return notices, evidence


def validate_program_headers(headers, abi):
    loads = 0
    relro = 0
    for line in headers.splitlines():
        fields = line.split()
        if fields and fields[0] == "LOAD":
            loads += 1
            if int(fields[-1], 16) < 16384:
                raise ValueError(f"LOAD alignment below 16 KB: {abi}")
        if fields and fields[0] == "GNU_RELRO":
            relro += 1
            if (int(fields[2], 16) + int(fields[5], 16)) % 16384:
                raise ValueError(f"RELRO end is not 16 KB aligned: {abi}")
    if loads == 0 or relro == 0:
        raise ValueError(f"Missing LOAD/RELRO program headers: {abi}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("source.lock.json"))
    for name in ("sdk", "ndk", "work", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.jobs < 1:
        raise ValueError("Build jobs must be positive")
    if args.output.exists():
        raise ValueError("Output directory already exists; choose a fresh directory")
    lock = json.loads(args.lock.read_text())
    cmake_dir = args.sdk / "cmake" / lock["cmake_revision"]
    if revision(args.ndk / "source.properties") != lock["ndk_revision"]:
        raise ValueError("NDK revision differs from source lock")
    if revision(cmake_dir / "source.properties") != lock["cmake_revision"]:
        raise ValueError("CMake revision differs from source lock")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if host is None:
        raise ValueError("This recipe supports Android NDK hosts Darwin and Linux")
    tools = args.ndk / "toolchains/llvm/prebuilt" / host / "bin"
    cmake, ninja = cmake_dir / "bin/cmake", cmake_dir / "bin/ninja"
    recipe = Path(__file__).with_name("CMakeLists.txt")
    inputs = {"lock": args.lock, "recipe": recipe, "script": Path(__file__), "clang": tools / "clang",
              "cmake": cmake, "ninja": ninja, "strip": tools / "llvm-strip", "readelf": tools / "llvm-readelf"}
    identities = {name: sha256(path) for name, path in inputs.items()}
    recipe_id = hashlib.sha256(json.dumps(identities, sort_keys=True).encode()).hexdigest()
    args.work.mkdir(parents=True, exist_ok=True)
    sources = {}
    for name, identity in lock["sources"].items():
        archive = prepare_archive(identity, args.work / "downloads")
        sources[name] = extract_verified_source(archive, identity, args.work / "sources" / name)
    build_root = args.work / recipe_id
    build_root.mkdir(exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix="outputs-", dir=build_root))
    publication = None
    receipt = {"schema_version": 1, "recipe_id": recipe_id, "host": host,
               "ndk_revision": lock["ndk_revision"], "cmake_revision": lock["cmake_revision"],
               "sources": lock["sources"], "input_sha256": identities, "abis": [], "licenses": []}
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", SOURCE_DATE_EPOCH=str(lock["source_date_epoch"]))
    for name in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "ASFLAGS", "CC", "CXX", "ASM",
                 "CMAKE_PREFIX_PATH", "CMAKE_INCLUDE_PATH", "CMAKE_LIBRARY_PATH", "CMAKE_TOOLCHAIN_FILE",
                 "CMAKE_GENERATOR", "CMAKE_GENERATOR_PLATFORM", "CMAKE_GENERATOR_TOOLSET"):
        env.pop(name, None)
    source_notices = {}
    try:
        for abi in lock["abis"]:
            build = build_root / abi
            if build.exists():
                shutil.rmtree(build)
            configure = [str(cmake), "-S", str(recipe.parent), "-B", str(build), "-G", "Ninja",
                         "-DCMAKE_MAKE_PROGRAM=" + str(ninja), "-DCMAKE_TOOLCHAIN_FILE=" + str(args.ndk / "build/cmake/android.toolchain.cmake"),
                         "-DSHIZUKU_SOURCE=" + str(sources["shizuku"]), "-DBORINGSSL_SOURCE=" + str(sources["boringssl"]),
                         "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-28", "-DANDROID_STL=c++_static",
                         "-DCMAKE_BUILD_TYPE=Release"]
            command = [str(cmake), "--build", str(build), "--target", "adb", "--parallel", str(args.jobs)]
            print(f"libadb: configure/build {abi}; logs: {build_root}", flush=True)
            run_logged(configure, build_root / (abi + "-configure.log"), env)
            run_logged(command, build_root / (abi + "-build.log"), env)
            dependencies = subprocess.check_output([str(ninja), "-C", str(build), "-t", "deps"], text=True)
            notices, notice_evidence = collect_source_notices(build / "compile_commands.json", sources["boringssl"], dependencies)
            source_notices.update(notices)
            output = stage / "jniLibs" / abi / "libadb.so"
            output.parent.mkdir(parents=True)
            shutil.copyfile(build / "libadb.so", output)
            subprocess.run([str(tools / "llvm-strip"), "--strip-unneeded", "--remove-section=.comment", str(output)], check=True)
            headers = subprocess.check_output([str(tools / "llvm-readelf"), "--program-headers", "--wide", str(output)], text=True)
            dynamic = subprocess.check_output([str(tools / "llvm-readelf"), "--dynamic", str(output)], text=True)
            symbols = subprocess.check_output([str(tools / "llvm-readelf"), "--dyn-syms", "--wide", str(output)], text=True)
            if "JNI_OnLoad" not in symbols or "libcrypto.so" in dynamic or "libc++_shared.so" in dynamic:
                raise ValueError(f"Unexpected libadb linkage: {abi}")
            validate_program_headers(headers, abi)
            receipt["abis"].append({"abi": abi, "bytes": output.stat().st_size, "sha256": sha256(output),
                                    "configure": configure, "build": command, "source_notice_evidence": notice_evidence,
                                    "program_headers": headers, "dynamic": dynamic, "dynamic_symbols": symbols})
        licenses = stage / "assets/licenses/libadb"
        licenses.mkdir(parents=True)
        for entry in lock["license_assets"]:
            source = args.ndk if entry["source"] == "ndk" else sources[entry["source"]]
            path = source / entry["source_path"].format(host=host)
            verify(path, entry)
            shutil.copyfile(path, licenses / entry["asset_name"])
            receipt["licenses"].append(entry)
        (licenses / "BoringSSL-source-notices.txt").write_text(
            "Notices from compiled BoringSSL sources, compiler-tracked includes and upstream assembly generators.\n"
            "Static linking may discard unused code; the component and nested Fiat LICENSEs also apply.\n\n" +
            "\n\n".join(path + "\n" + notice for path, notice in sorted(source_notices.items())) + "\n")
        (licenses / "NOTICE.txt").write_text(
            "Droidance libadb wireless ADB pairing JNI library.\n"
            "Shizuku pairing implementation (Apache-2.0), unmodified source:\n" + lock["sources"]["shizuku"]["repository"] +
            "/tree/" + lock["sources"]["shizuku"]["commit"] + "\n"
            "BoringSSL crypto implementation, statically linked from source:\n" + lock["sources"]["boringssl"]["repository"] +
            "/tree/" + lock["sources"]["boringssl"]["commit"] + "\n"
            "Its adjacent LICENSE includes OpenSSL, SSLeay, ISC and fiat-crypto MIT terms.\n"
            "The nested Fiat LICENSE is also retained in full, including its updated copyright years.\n"
            "This product includes software developed by the OpenSSL Project for use in the OpenSSL Toolkit (http://www.openssl.org/).\n"
            "This product includes cryptographic software written by Eric Young (eay@cryptsoft.com).\n"
            "This product includes software written by Tim Hudson (tjh@cryptsoft.com).\n"
            "NDK C++ runtime notices are retained in the adjacent NDK notice.\n"
            "The Droidance build recipe changes compiler/linker settings, not the upstream JNI protocol.\n")
        receipt["status"] = "SOURCE_BUILD_PASS_NOT_DEVICE_TESTED"
        evidence = [entry["source_notice_evidence"] for entry in receipt["abis"]]
        receipt["source_notice_summary"] = {
            "compiled_sources": len(set().union(*(set(item["compiled_sources"]) for item in evidence))),
            "dependency_sources": len(set().union(*(set(item["dependency_sources"]) for item in evidence))),
            "generated_assembly_inputs": len(set().union(*(set(item["generated_assembly_inputs"]) for item in evidence))),
            "dependencies_covered_by_fiat_license": len(set().union(*(set(item["dependencies_covered_by_fiat_license"]) for item in evidence))),
            "retained_notices": len(source_notices)
        }
        receipt["license_output_sha256"] = {path.name: sha256(path) for path in sorted(licenses.iterdir())}
        (stage / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
        provenance = {
            "schema_version": 1,
            "description": "Droidance libadb binaries built from the adjacent locked upstream sources and recipe.",
            "recipe_input_sha256": {"source.lock.json": identities["lock"], "build_android.py": identities["script"],
                                    "CMakeLists.txt": identities["recipe"]},
            "ndk_revision": lock["ndk_revision"], "cmake_revision": lock["cmake_revision"],
            "build_host": host, "tool_sha256": {name: identity for name, identity in identities.items()
                                               if name not in ("lock", "script", "recipe")},
            "libraries": {entry["abi"]: {key: entry[key] for key in ("bytes", "sha256")} for entry in receipt["abis"]},
            "license_output_sha256": receipt["license_output_sha256"],
            "source_notice_summary": receipt["source_notice_summary"],
            "verification": "Source digest, JNI export, static linkage and 16 KB LOAD/RELRO checks passed; device acceptance is separate."
        }
        (stage / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        if args.output.exists():
            raise ValueError("Output directory already exists; choose a fresh directory")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        publication = Path(tempfile.mkdtemp(prefix=".libadb-output-", dir=args.output.parent))
        shutil.copytree(stage, publication, dirs_exist_ok=True)
        publication.rename(args.output)
        print(f"libadb source build complete: {args.output / 'receipt.json'}", flush=True)
    finally:
        shutil.rmtree(stage)
        if publication is not None and publication.exists():
            shutil.rmtree(publication)


if __name__ == "__main__":
    main()
