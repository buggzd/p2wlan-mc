import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
import class_relocation as relocation
from artifact_checks import VerificationError


def utf8(value):
    return b"\x01" + len(value).to_bytes(2, "big") + value


def class_bytes(constants, count=None, body=b"\x00" * 14):
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x3d" + \
        (count or len(constants) + 1).to_bytes(2, "big") + b"".join(constants) + body


class ClassRelocationTests(unittest.TestCase):
    def test_embedded_neutral_dispatcher_is_never_relocated_as_a_fabric_game_class(self):
        name = "io/p2wlan/mc/bootstrap/UniversalEnvironment.class"
        value = class_bytes([utf8(b"io.p2wlan.mc.game.server.mixin"), utf8(b"io/p2wlan/mc/game/World")])
        self.assertEqual(relocation.relocate_member(name, value), (name, value))
        self.assertNotEqual(relocation.relocate_class(value), value)

    def test_version_namespaces_are_fixed_and_relocation_is_idempotent(self):
        for destination in (relocation.NEW, relocation.LEGACY, relocation.FABRIC_LEGACY):
            original = class_bytes([utf8((relocation.OLD + "Example;" + destination + "Already").encode())])
            moved = relocation.relocate_class(original, destination)
            self.assertEqual(moved, relocation.relocate_class(moved, destination))
            name = relocation.relocate_name(relocation.OLD + "Example.class", destination)
            self.assertEqual(name, destination + "Example.class")
            self.assertEqual(name, relocation.relocate_name(name, destination))
        with self.assertRaisesRegex(VerificationError, "namespace"):
            relocation.relocate_class(class_bytes([utf8(b"value")]), "arbitrary/owner/")

    def test_fabric20_login_mixin_relocates_owned_references_without_changing_api_target(self):
        from verify_fabric_legacy_jar import LOGIN_MIXIN
        constants = [LOGIN_MIXIN.removesuffix('.class').encode(),
                     b'Lio/p2wlan/mc/game/client/connect/GameJoins;',
                     b'net/fabricmc/fabric/impl/networking/client/ClientNetworkingImpl',
                     b'getLoginConnection']
        original = class_bytes([utf8(value) for value in constants])
        expected = class_bytes([utf8(value.replace(relocation.OLD.encode(), relocation.FABRIC_LEGACY.encode()))
                                for value in constants])
        moved = relocation.relocate_class(original, relocation.FABRIC_LEGACY)
        self.assertEqual(moved, expected)
        self.assertEqual(relocation.relocate_name(LOGIN_MIXIN, relocation.FABRIC_LEGACY),
                         'io/p2wlan/mc/fabric/game/v1_20_1/mixin/FabricLoginNetworkingMixin.class')
        self.assertIn(constants[2], moved); self.assertIn(constants[3], moved)

    def test_only_utf8_payloads_change_and_modified_utf8_is_preserved(self):
        old = relocation.OLD.encode()
        new = relocation.NEW.encode()
        text = b"L" + old + b"Example<TT;>;\xc0\x80\xed\xa0\xbd\xed\xb8\x80"
        body = old + b"\x00" * 14
        original = class_bytes([utf8(text), b"\x03\x00\x00\x00\x01"], body=body)
        expected = class_bytes([utf8(text.replace(old, new)), b"\x03\x00\x00\x00\x01"], body=body)
        self.assertEqual(relocation.relocate_class(original), expected)
        self.assertEqual(relocation.relocate_class(expected), expected)

    def test_all_constant_tags_and_double_slot_entries_keep_their_indices(self):
        sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
                 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
        constants = [bytes([tag]) + b"\x00" * size for tag, size in sizes.items()]
        source = class_bytes(constants, count=len(constants) + 3)
        self.assertEqual(relocation.relocate_class(source), source)

    def test_invalid_pool_or_growth_fails_before_any_result_is_returned(self):
        valid = class_bytes([utf8(b"value")])
        for source in (b"", valid[:9], class_bytes([b"\x02"]), valid[:14],
                       class_bytes([b"\x05" + b"\x00" * 8]), valid[:-1],
                       class_bytes([utf8((relocation.OLD * 3000).encode())])):
            with self.subTest(size=len(source)):
                with self.assertRaises(VerificationError):
                    relocation.relocate_class(source)

    def test_json_rewrites_only_class_keys_and_config_fields(self):
        mapping = {relocation.OLD + "mixin/Example": {"method": "Lnet/minecraft/class_1;method_1()V"}}
        original = {"mappings": mapping, "data": {"named:intermediary": mapping}}
        name = next(iter(relocation.REFMAPS))
        destination, value = relocation.relocate_member(name, json.dumps(original).encode())
        self.assertEqual(destination, relocation.REFMAPS[name])
        moved = json.loads(value)
        self.assertEqual(moved["mappings"][relocation.NEW + "mixin/Example"], mapping[next(iter(mapping))])
        config = {"package": "io.p2wlan.mc.game.mixin", "refmap": name, "client": ["Example"]}
        _, value = relocation.relocate_member("p2wlan.client.mixins.json", json.dumps(config).encode())
        self.assertEqual(json.loads(value), {**config, "package": "io.p2wlan.mc.fabric.game.mixin", "refmap": relocation.REFMAPS[name]})
        for source in (b'{"package":"a","package":"b"}', b"[]", b"{}"):
            with self.assertRaises(ValueError):
                relocation.relocate_member("p2wlan.client.mixins.json", source)
        with self.assertRaisesRegex(VerificationError, "key_collision"):
            relocation.relocate_keys({relocation.OLD + "Example": 1, relocation.NEW + "Example": 2})
        unrelated = b"arbitrary " + relocation.OLD.encode()
        self.assertEqual(relocation.relocate_member("README.txt", unrelated), ("README.txt", unrelated))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required for bytecode execution")
    def test_jvm_executes_relocated_descriptors_annotations_generics_and_reflection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Main.java"
            source.write_text('''package io.p2wlan.mc.game;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @interface Type { Class<?> value(); }
@Type(Main.Inner.class)
public class Main {
    record Inner(long count, double number) { }
    java.util.List<Inner> items = java.util.List.of(new Inner(1234567890123456789L, 1.25));
    public static void main(String[] args) throws Exception {
        var value = new Main().items.get(0);
        if (value.count() != 1234567890123456789L || value.number() != 1.25) throw new AssertionError();
        if (Main.class.getAnnotation(Type.class).value() != Inner.class) throw new AssertionError();
        if (Class.forName("io.p2wlan.mc.game.Main") != Main.class) throw new AssertionError();
        if (!Main.class.getDeclaredField("items").getGenericType().getTypeName().contains(Main.class.getName() + "$Inner")) throw new AssertionError();
        if (!"你好🌍".equals("你好🌍")) throw new AssertionError();
        System.out.print(Main.class.getName());
    }
}''', encoding="utf-8")
            classes = root / "classes"
            subprocess.run([shutil.which("javac"), "--release", "17", "-d", str(classes), str(source)], check=True, capture_output=True, timeout=30)
            for index, destination in enumerate((relocation.NEW, relocation.LEGACY, relocation.FABRIC_LEGACY)):
                result = root / f"relocated-{index}.jar"
                with zipfile.ZipFile(result, "w") as archive:
                    for path in sorted(classes.rglob("*.class")):
                        name = relocation.relocate_name(path.relative_to(classes).as_posix(), destination)
                        archive.writestr(name, relocation.relocate_class(path.read_bytes(), destination))
                entrypoint = destination.replace("/", ".") + "Main"
                run = subprocess.run([shutil.which("java"), "-Xverify:all", "-cp", str(result), entrypoint], check=True, capture_output=True, text=True, timeout=30)
                self.assertEqual(run.stdout, entrypoint)


if __name__ == "__main__":
    unittest.main()
