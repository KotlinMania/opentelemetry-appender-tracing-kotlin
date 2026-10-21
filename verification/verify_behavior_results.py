import argparse
import pathlib
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
parser.add_argument('--require', action='append', default=[])
args = parser.parse_args()
files = list(args.directory.rglob('TEST-*.xml'))
assert files, f'No actual test XML in {args.directory}'
cases = []
for file in files:
    root = ET.parse(file).getroot()
    cases.extend(root.iter('testcase'))
assert cases, 'Reports contain no executed test cases'
for case in cases:
    assert not any(case.find(kind) is not None for kind in ('failure', 'error', 'skipped')), f"Unsuccessful test: {case.get('classname')}.{case.get('name')}"
for required in args.require:
    assert any(required in case.get('name', '') for case in cases), f'Required behavioral test did not execute: {required}'
print(f'{args.directory}: {len(cases)} executed and passed; 0 failed, 0 skipped; required names verified')
