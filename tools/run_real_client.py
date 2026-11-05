# The closest test to really playing: starts the real game the way the Modrinth app does, with the same Forge and Embeddium, in a throwaway folder (run-real/).
# The jar must be built with -Psmoke so ClientChecks is in it: it walks through everything, saves screenshots to run-real/screenshots and quits.
#   gradlew build -Psmoke -x test   then   python tools/run_real_client.py [reopen] [fps=on|off] [extra=<jar>]
import glob
import json
import os
import shutil
import subprocess
import sys

M = os.path.join(os.environ['APPDATA'], 'ModrinthApp')
META = os.path.join(M, 'meta')
VERSION = '1.20.1-47.4.28'
JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
PROFILE_MODS = os.path.join(M, 'profiles', 'Powers', 'mods')
NEEDED = ['embeddium-']
RUN = os.path.abspath('run-real')
TIMEOUT = 540


def allowed(rules):
    ok = not rules
    for r in rules or []:
        name = (r.get('os') or {}).get('name')
        if name in (None, 'windows') and 'features' not in r:
            ok = r['action'] == 'allow'
    return ok


def library_path(lib):
    art = (lib.get('downloads') or {}).get('artifact')
    if art and art.get('path'):
        return art['path']
    group, name, ver = lib['name'].split(':')[:3]
    return f"{group.replace('.', '/')}/{name}/{ver}/{name}-{ver}.jar"


def main():
    reopen = 'reopen' in sys.argv
    fps = next((a[4:] for a in sys.argv if a.startswith('fps=')), '')
    d = json.load(open(os.path.join(META, 'versions', VERSION, VERSION + '.json'), encoding='utf-8'))
    cp, seen = [], set()
    for lib in d['libraries']:
        if lib.get('include_in_classpath') is False or not allowed(lib.get('rules')) or 'natives' in lib['name'].split(':')[-1] and 'windows' not in lib['name']:
            continue
        path = os.path.join(META, 'libraries', library_path(lib))
        if os.path.exists(path) and path not in seen:
            seen.add(path)
            cp.append(path)
    cp.append(os.path.join(META, 'versions', VERSION, VERSION + '.jar'))

    os.makedirs(os.path.join(RUN, 'mods'), exist_ok=True)
    # A fresh game folder opens the first-launch narrator screen and waits forever otherwise.
    options = os.path.join(RUN, 'options.txt')
    lines = open(options, encoding='utf-8').read().splitlines() if os.path.exists(options) else []
    lines = [line for line in lines if not line.startswith('onboardAccessibility:')] + ['onboardAccessibility:false']
    with open(options, 'w', encoding='utf-8') as f:
        f.write('
'.join(lines) + '
')
    if not reopen and not fps:
        shutil.rmtree(os.path.join(RUN, 'saves', 'smoke'), ignore_errors=True)
        shutil.rmtree(os.path.join(RUN, 'charmap'), ignore_errors=True)
    if os.path.exists(os.path.join(RUN, 'config', 'charmap.properties')):
        os.remove(os.path.join(RUN, 'config', 'charmap.properties'))
    shots = os.path.join(RUN, 'screenshots')
    if not reopen and not fps:
        shutil.rmtree(shots, ignore_errors=True)
    for old in glob.glob(os.path.join(RUN, 'mods', '*.jar')):
        os.remove(old)
    for name in os.listdir(PROFILE_MODS):
        if name.endswith('.jar') and any(name.startswith(n) for n in NEEDED):
            shutil.copy(os.path.join(PROFILE_MODS, name), os.path.join(RUN, 'mods', name))
    for jar in [a[len('extra='):] for a in sys.argv if a.startswith('extra=')]:
        shutil.copy(jar, os.path.join(RUN, 'mods', os.path.basename(jar)))
    built = sorted(glob.glob('build/libs/charmap-*.jar'), key=os.path.getmtime)[-1]
    shutil.copy(built, os.path.join(RUN, 'mods', 'charmap-smoke.jar'))
    print('mods:', sorted(os.listdir(os.path.join(RUN, 'mods'))))

    natives = os.path.join(META, 'natives', VERSION)
    values = {'natives_directory': natives, 'launcher_name': 'charmap-test', 'launcher_version': '1', 'classpath': os.pathsep.join(cp), 'classpath_separator': os.pathsep,
              'library_directory': os.path.join(META, 'libraries'), 'version_name': VERSION, 'auth_player_name': 'Dev', 'game_directory': RUN, 'assets_root': os.path.join(META, 'assets'),
              'assets_index_name': d['assets'], 'auth_uuid': '00000000000000000000000000000abc', 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy', 'version_type': 'release'}

    def fill(text):
        for key, value in values.items():
            text = text.replace('${' + key + '}', value)
        return text

    def flat(args):
        out = []
        for a in args:
            if isinstance(a, str):
                out.append(fill(a))
            elif allowed(a.get('rules')):
                v = a['value']
                out.extend(fill(x) for x in ([v] if isinstance(v, str) else v))
        return out

    flags = ['-Dcharmap.smokeclient=true'] + (['-Dcharmap.reopen=true'] if reopen else []) + (['-Dcharmap.fpsonly=' + fps] if fps else [])
    cmd = [JAVA, '-Xmx2500M'] + flags + flat(d['arguments']['jvm']) + [d['mainClass']] + flat(d['arguments']['game']) + ['--width', '1600', '--height', '900']
    with open(os.path.join(RUN, 'launch.log'), 'w', encoding='utf-8', errors='replace') as log:
        try:
            result = subprocess.run(cmd, cwd=RUN, stdout=log, stderr=subprocess.STDOUT, timeout=TIMEOUT)
            print('exit', result.returncode)
        except subprocess.TimeoutExpired:
            print('timed out after', TIMEOUT, 's')
    print('screenshots:', len(glob.glob(os.path.join(RUN, 'screenshots', '*.png'))))
    return 0


if __name__ == '__main__':
    sys.exit(main())
