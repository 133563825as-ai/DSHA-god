#!/usr/bin/env python3
"""Synthetic whole-module output contract checks; no runtime or user module is written."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import runtime_stable_patches as patches


class OutputIdentityTest(unittest.TestCase):
    def sequence(self):
        expected = b'current semantics patched'
        sequence = patches.PatchSequence(expected_output_sha256=hashlib.sha256(expected).hexdigest())
        sequence.append(('anchor', 'patched'))
        return sequence, expected

    def test_local_anchors_cannot_admit_old_semantics(self):
        sequence, expected = self.sequence()
        chain = [('strict', sequence, [])]
        self.assertEqual(expected, patches.apply(b'current semantics anchor', chain))
        self.assertEqual(expected, patches.apply(expected, chain))
        for source in (b'old semantics anchor', b'old semantics patched', expected + b' suffix'):
            with self.assertRaisesRegex(ValueError, 'STABLE_PATCH_OUTPUT_IDENTITY_MISMATCH'):
                patches.apply(source, chain)

    def test_marker_skip_still_checks_output(self):
        sequence, expected = self.sequence()
        chain = [('strict', sequence, ['patched'])]
        self.assertEqual(expected, patches.apply(expected, chain))
        with self.assertRaisesRegex(ValueError, 'STABLE_PATCH_OUTPUT_IDENTITY_MISMATCH'):
            patches.apply(b'old semantics patched', chain)

    def test_unopted_recipe_keeps_original_behavior(self):
        self.assertEqual(b'old semantics patched',
                         patches.apply(b'old semantics anchor', [('legacy', [('anchor', 'patched')], [])]))

    def test_invalid_declared_digest_is_not_silently_disabled(self):
        for digest in ('', 'UPPER' * 13, True, 5):
            with self.assertRaisesRegex(ValueError, 'STABLE_PATCH_OUTPUT_IDENTITY_INVALID'):
                patches.PatchSequence(expected_output_sha256=digest)
        with tempfile.TemporaryDirectory(prefix='stable-patch-output-') as temporary:
            root = Path(temporary)
            (root / 'runtime-patches.json').write_text(json.dumps({
                'schema': 1, 'dshVersion': 'test', 'active': [{'asset': 'test.json'}]}))
            (root / 'test.json').write_text(json.dumps({
                'dshVersion': 'test', 'module': 'test/index.js', 'patches': [],
                'expectedOutputSha256': None}))
            with self.assertRaisesRegex(ValueError, 'STABLE_PATCH_OUTPUT_IDENTITY_INVALID'):
                patches.load_chains(root, 'test')


if __name__ == '__main__':
    unittest.main()
