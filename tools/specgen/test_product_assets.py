"""CLI subprocess boundary checks; no kernel, compiler, or SDK execution."""
import importlib.util
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("product_assets_candidate", HERE / "specgen/product.py")
product = importlib.util.module_from_spec(spec)
spec.loader.exec_module(product)


class ProductAssetsTest(unittest.TestCase):
    def arguments(self, **changes):
        fields = dict(symbols=None, struct_offsets=None,
                      recipe=product.ROOT / "tools/specgen/specgen/recipes/k22.json",
                      java=None, image_core=None, out=Path("output.spec"), boot=Path("boot.img"))
        fields.update(changes)
        return SimpleNamespace(**fields)

    def invoke(self, args):
        with patch.object(product, "_host_runtime", return_value=("java", Path("host.jar"), "deps")), \
                patch.object(product.subprocess, "run", return_value=subprocess.CompletedProcess([], 7)) as run:
            result = product.generate(args)
        self.assertEqual(result, 7)
        command = run.call_args.args[0]
        self.assertEqual(run.call_args.kwargs["stdin"], subprocess.DEVNULL)
        self.assertNotIn("input", run.call_args.kwargs)
        self.assertEqual(command.count("--assets"), 1)
        return command[command.index("--assets") + 1]

    def test_existing_programmatic_caller_defaults_to_repository_assets(self):
        self.assertEqual(self.invoke(self.arguments()),
                         str((product.ROOT / "manager/src/main/assets").resolve()))

    def test_unspecified_parser_assets_defaults_to_repository_assets(self):
        self.assertEqual(self.invoke(self.arguments(assets=None)),
                         str((product.ROOT / "manager/src/main/assets").resolve()))

    def test_explicit_generation_assets_remains_one_argument(self):
        root = Path("generation A assets")
        self.assertEqual(self.invoke(self.arguments(assets=root)), str(root.resolve()))


if __name__ == "__main__":
    unittest.main()
