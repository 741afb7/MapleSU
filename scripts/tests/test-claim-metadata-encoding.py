"""Lossless claim publisher and delivered-APK metadata verification, without native builds."""
import argparse
import ast
import gzip
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path
import re
import sys
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
PARSER = argparse.ArgumentParser()
PARSER.add_argument('--verifier', type=Path, default=ROOT / 'scripts/verify-release-apk.py')
ARGS, UNITTEST_ARGS = PARSER.parse_known_args()
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location('claim_metadata_verifier', ARGS.verifier)
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
TREE = ast.parse((ROOT / 'scripts/build-ultra-manager-claim.py').read_text(encoding='utf-8-sig'))
FUNCTIONS = {'write_runtime_manifest', 'claim_release_version', 'identity', 'sha'}
CODE = compile(ast.Module(body=[node for node in TREE.body if isinstance(node, ast.FunctionDef)
    and node.name in FUNCTIONS], type_ignores=[]), 'claim-publisher', 'exec')
FAMILIES = ['android12-5.10', 'android13-5.15', 'android14-6.1', 'android15-6.6', 'android16-6.12', 'android17-6.18']


class ClaimMetadataEncodingTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='claim-metadata-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.assets, self.out = self.root / 'assets', self.root / 'out'
        self.assets.mkdir(); self.out.mkdir()
        (self.root / 'manager').mkdir()
        (self.root / 'manager/build.gradle.kts').write_text('versionName = "5.2.1"\nversionCode = 50201\n')
        self.env = dict(ROOT=self.root, Path=Path, re=re, lzma=lzma, hashlib=hashlib, json=json)
        exec(CODE, self.env)
        self.native = b'\x01\x02\x03'
        self.payload = dict(bytes=len(self.native), sha256=M.digest(self.native), facts={'complete': '完整 ABI', 'values': list(range(20))})
        rows = []
        for family in FAMILIES:
            (self.assets / family).mkdir(); (self.out / family).mkdir()
            (self.assets / family / 'claim.so').write_bytes(self.native)
            rows.append(dict(kernel=family, payload=self.payload))
        support = {}
        for name in ('bootstrap', 'materializer', 'permissions'):
            body = name.encode()
            (self.assets / (name + '.bin')).write_bytes(body)
            support[name] = dict(bytes=len(body), sha256=M.digest(body))
        self.env['write_runtime_manifest'](self.assets, self.out, {}, support, rows)
        self.manifest = json.loads((self.assets / 'manifest.json').read_bytes())
        self.family = FAMILIES[0]
        self.row = self.manifest['payloads'][self.family]
        self.full = (self.out / self.family / 'runtime-payload-metadata.full.json').read_bytes()

    def encode(self, codec='xz', data=None):
        if data is None:
            data = lzma.compress(self.full, preset=6) if codec == 'xz' else gzip.compress(self.full, mtime=0)
        row = self.row
        for old in row['chunks']:
            (self.assets / old['path']).unlink()
        path = self.family + '/test.' + codec
        (self.assets / path).write_bytes(data)
        row.update(metadataEncoding=codec + '-chunks-v1', chunks=[dict(path=path, bytes=len(data), sha256=M.digest(data))])
        row.pop('xzBytes', None); row.pop('xzSha256', None)
        row[codec + 'Bytes'] = len(data); row[codec + 'Sha256'] = M.digest(data)

    def verify(self):
        (self.assets / 'manifest.json').write_text(json.dumps(self.manifest), encoding='utf-8')
        sdk = self.root / 'SDK-RELEASE.json'
        sdk.write_text(json.dumps(dict(appVersion='5.2.1', versionCode=50201)))
        execution = dict(claimManifest=M.record(self.assets / 'manifest.json'), sdkRelease=M.record(sdk))
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for path in self.assets.rglob('*'):
                if path.is_file(): archive.writestr('assets/ultra-manager-claim/' + path.relative_to(self.assets).as_posix(), path.read_bytes())
        with zipfile.ZipFile(output) as archive:
            receipt = {}
            M.verify_claim_assets(archive, set(archive.namelist()), execution, receipt)
        return receipt

    def test_actual_publisher_xz_is_accepted_without_losing_facts(self):
        receipt = self.verify()
        self.assertEqual(len(receipt['claimAssetBindings']), 15)
        self.assertEqual(json.loads(lzma.decompress(b''.join((self.assets / c['path']).read_bytes() for c in self.row['chunks']))), self.payload)

    def test_legacy_gzip_metadata_remains_accepted(self):
        self.encode('gzip')
        self.verify()

    def test_unknown_encoding_is_rejected(self):
        self.row['metadataEncoding'] = 'other-chunks-v1'
        with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_ENCODING'): self.verify()

    def test_aggregate_identity_is_rejected_before_decode(self):
        self.row['xzSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_AGGREGATE'): self.verify()

    def test_decoded_identity_and_expansion_limit_are_enforced(self):
        for key, value in [('decodedSha256', '0' * 64), ('decodedBytes', len(self.full) - 1),
                           ('decodedBytes', len(self.full) + 1), ('decodedBytes', 32 * 1048576 + 1)]:
            with self.subTest(key=key, value=value):
                original = self.row[key]; self.row[key] = value
                with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_DECODE'): self.verify()
                self.row[key] = original

    def test_truncated_xz_is_rejected_with_correct_encoded_hashes(self):
        self.encode(data=lzma.compress(self.full, preset=6)[:-3])
        with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_DECODE'): self.verify()

    def test_xz_dictionary_memory_limit_is_enforced(self):
        encoded = bytearray(lzma.compress(self.full, preset=6))
        header_bytes = (encoded[12] + 1) * 4
        self.assertEqual(encoded[13:16], b'\x00\x21\x01')
        encoded[16] = 28
        checksum = zlib.crc32(encoded[12:12 + header_bytes - 4])
        encoded[12 + header_bytes - 4:12 + header_bytes] = checksum.to_bytes(4, 'little')
        self.encode(data=encoded)
        with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_DECODE'): self.verify()

    def test_payload_native_identity_is_not_replaced_by_metadata_hash(self):
        changed = json.dumps(dict(self.payload, bytes=9)).encode()
        self.encode(data=lzma.compress(changed, preset=6))
        self.row.update(decodedBytes=len(changed), decodedSha256=M.digest(changed))
        with self.assertRaisesRegex(ValueError, 'CLAIM_METADATA_PAYLOAD_IDENTITY'): self.verify()


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *UNITTEST_ARGS])
