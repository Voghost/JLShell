import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('slim', Path(__file__).with_name('slim_distribution.py'))
slim = importlib.util.module_from_spec(spec); spec.loader.exec_module(slim)

class SlimDistributionTest(unittest.TestCase):
    def test_native_matrix_and_provided_javafx_preserve_runtime_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); source=root/'source.jar'
            entries={'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\n',
                     'META-INF/services/java.sql.Driver':b'org.sqlite.JDBC',
                     'META-INF/LICENSE':b'license', 'icons/brand/jlshell.png':b'brand',
                     'icons/app_icon.png':b'duplicate', 'com/jlshell/app/Launcher.class':b'app',
                     'com/sun/jna/aix-ppc64/libjnidispatch.a':b'unsupported',
                     'javafx/application/Application.class':b'fx', 'org/bouncycastle/reflective.class':b'keep'}
            for os,arch in [('Mac','aarch64'),('Mac','x86_64'),('Linux','x86_64'),('Windows','x86_64'),('Linux-Android','arm')]:
                entries[f'org/sqlite/native/{os}/{arch}/native.so']=b'native'
            with zipfile.ZipFile(source,'w') as z:
                for name,data in entries.items():z.writestr(name,data)
            deps=root/'javafx-graphics/21';deps.mkdir(parents=True)
            with zipfile.ZipFile(deps/'javafx.jar','w') as z:z.writestr('javafx/application/Application.class',b'fx')
            for target in ['universal',*slim.TARGETS]:
                output=root/(target+'.jar');slim.slim(source,output,target,root if target!='universal' else None)
                with zipfile.ZipFile(output) as z:
                    names=set(z.namelist())
                    self.assertIn('META-INF/LICENSE',names);self.assertIn('META-INF/services/java.sql.Driver',names)
                    self.assertIn('org/bouncycastle/reflective.class',names)
                    self.assertNotIn('icons/app_icon.png',names)
                    self.assertNotIn('com/sun/jna/aix-ppc64/libjnidispatch.a',names)
                    self.assertFalse(any('Android' in n for n in names))
                    self.assertEqual(target=='universal','javafx/application/Application.class' in names)
                    self.assertEqual(4 if target=='universal' else 1,len([n for n in names if n.startswith('org/sqlite/native/')]))

if __name__=='__main__':unittest.main()
