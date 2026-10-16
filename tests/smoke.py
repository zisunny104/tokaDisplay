from pathlib import Path
import subprocess,zipfile,shutil,datetime
root=Path(__file__).resolve().parents[1]
java=Path('C:/Users/zisun/.jdks/jbr-21.0.11/bin')
core=root.parent/'Nukkit/target/nukkit-1.0-SNAPSHOT.jar'
zero=root.parent/'zero/output/zero-1.7.16.jar'
classes=root/'target/smoke-classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac.exe'),'-proc:none','-encoding','UTF-8','-cp',str(core)+';'+str(zero),'-d',str(classes),str(root/'tests/DisplaySmokeProbe.java')],check=True)
probe=root/'target/DisplaySmokeProbe.jar'
with zipfile.ZipFile(probe,'w') as jar:
 jar.write(classes/'DisplaySmokeProbe.class','DisplaySmokeProbe.class')
 jar.writestr('plugin.yml','name: DisplaySmokeProbe\nmain: DisplaySmokeProbe\nversion: test\napi: ["1.0.4"]\nsoftdepend: [zero, tokaDisplay]\n')
for enabled in (True,False):
 run=root/'target'/('smoke-'+str(enabled)+'-'+datetime.datetime.now().strftime('%H%M%S'));(run/'plugins/zero').mkdir(parents=True)
 shutil.copy2(zero,run/'plugins'/zero.name);shutil.copy2(probe,run/'plugins'/probe.name)
 if enabled:shutil.copy2(root/'output/tokaDisplay-0.1.0.jar',run/'plugins/tokaDisplay-0.1.0.jar')
 (run/'plugins/zero/config.yml').write_text('database:\n  mode: disabled\nworlds:\n  load: []\n  generate-missing: []\n')
 (run/'nukkit.yml').write_text('settings:\n  language: cht\n  force-language: false\n')
 (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=19149\nlevel-name=test\nlevel-type=void\nview-distance=1\nxbox-auth=off\nauto-save=off\nenable-query=off\nallow-nether=off\nallow-the-end=off\n')
 with (run/'console.log').open('wb') as output:
  process=subprocess.Popen([str(java/'java.exe'),'-Xms128m','-Xmx384m','-Dprobe.display='+str(enabled).lower(),'-jar',str(core)],cwd=run,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT)
  try:process.wait(timeout=90)
  except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=15);raise RuntimeError('Smoke timeout: '+str(run))
 log=(run/'console.log').read_text(errors='replace')
 if 'DISPLAY_SMOKE_PASS' not in log or 'DISPLAY_SMOKE_FAIL' in log:raise RuntimeError('Smoke failed: '+str(run/'console.log'))
 print('DISPLAY_SMOKE_PASS display='+str(enabled),flush=True)
