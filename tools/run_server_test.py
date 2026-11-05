# A real test of the server half: starts the real Arclight server jar in a throwaway folder with this mod and a world that has generated chunks,
# then the real game joins it and fills its map from the server. Needs a jar built with -Psmoke, and run-real/saves/smoke from an earlier run_real_client.py.
# The server's libraries come from another throwaway folder that already has them (nothing is downloaded): --from <folder with libraries and .arclight>
#   python tools/run_server_test.py --from ../charboost/run-arclight/alone
import argparse
import glob
import os
import queue
import shutil
import subprocess
import sys
import threading
import time

JAR = r'C:\Users\black\Downloads\arclight-forge-1.20.1-1.0.6-SNAPSHOT-5dc8683.jar'
JAVA = r'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
RUN = os.path.abspath('run-arclight')
PORT = 25599
PROPS = f"""online-mode=false
enforce-secure-profile=false
server-port={PORT}
level-name=world
level-seed=20261105
view-distance=6
simulation-distance=4
spawn-protection=0
max-players=4
difficulty=peaceful
"""


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--from', dest='source', required=True)
    p.add_argument('--limit', type=int, default=240)
    a = p.parse_args()

    os.makedirs(RUN, exist_ok=True)
    for name in ['libraries', '.arclight']:
        if not os.path.exists(os.path.join(RUN, name)):
            shutil.copytree(os.path.join(a.source, name), os.path.join(RUN, name))
    for name in os.listdir(a.source):
        full = os.path.join(a.source, name)
        if os.path.isfile(full) and (name.endswith('.jar') or name in ('arclight.conf', 'eula.txt')):
            shutil.copy(full, os.path.join(RUN, name))
    with open(os.path.join(RUN, 'eula.txt'), 'w') as f:
        f.write('eula=true\n')
    with open(os.path.join(RUN, 'server.properties'), 'w') as f:
        f.write(PROPS)
    shutil.rmtree(os.path.join(RUN, 'mods'), ignore_errors=True)
    os.makedirs(os.path.join(RUN, 'mods'))
    built = sorted(glob.glob('build/libs/charmap-*.jar'), key=os.path.getmtime)[-1]
    shutil.copy(built, os.path.join(RUN, 'mods', 'charmap-smoke.jar'))
    # A world with generated chunks, region files only: the server makes the rest itself from the same seed.
    world = os.path.join(RUN, 'world')
    shutil.rmtree(world, ignore_errors=True)
    os.makedirs(world)
    shutil.copytree(os.path.join('run-real', 'saves', 'smoke', 'region'), os.path.join(world, 'region'))
    print('regions on the server:', len(os.listdir(os.path.join(world, 'region'))))

    server = subprocess.Popen([JAVA, '-Xmx1500M', '-jar', JAR, 'nogui'], cwd=RUN, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    lines = queue.Queue()
    log = open(os.path.join(RUN, 'console.txt'), 'w', encoding='utf-8', errors='replace')

    def pump():
        for line in server.stdout:
            log.write(line)
            log.flush()
            lines.put(line)

    threading.Thread(target=pump, daemon=True).start()
    try:
        started = time.time()
        ready = False
        while time.time() - started < a.limit and server.poll() is None:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if 'Done (' in line:
                ready = True
                break
            if 'Exception' in line or 'FATAL' in line or 'charmap' in line.lower():
                print('server:', line.strip()[:200])
        print('server up after', round(time.time() - started), 's:', ready)
        if not ready:
            return 1
        for visit in range(2):
            client = subprocess.run([sys.executable, 'tools/run_real_client.py', f'join=localhost:{PORT}'], capture_output=True, text=True, timeout=240)
            print('visit', visit + 1, client.stdout[-120:])
            log_file = os.path.join('run-real', 'logs', 'latest.log')
            with open(log_file, encoding='utf-8', errors='replace') as f:
                found = [line.split(': ', 1)[-1][:120] for line in f if 'from the server so far' in line or 'server fill finished' in line or 'server is sending' in line]
            print('\n'.join(found[:1] + found[-2:]))
        # Give the last lines a moment, then stop properly.
        server.stdin.write('stop\n')
        server.stdin.flush()
        try:
            server.wait(60)
        except subprocess.TimeoutExpired:
            server.kill()
        print('server exit code', server.returncode)
        while not lines.empty():
            line = lines.get()
            if 'CharMap' in line or 'charmap' in line or 'Exception' in line or 'lost connection' in line:
                print('server:', line.strip()[:220])
        return 0
    finally:
        if server.poll() is None:
            server.kill()
        log.close()


if __name__ == '__main__':
    sys.exit(main())
