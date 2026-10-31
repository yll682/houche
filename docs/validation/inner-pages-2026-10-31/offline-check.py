from pathlib import Path
import subprocess, shutil, sys
base=Path('/data/repos/houche');tmp=Path('/tmp/houche-check');tmp.mkdir(exist_ok=True)
java='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
cache=Path('/home/user/.gradle/caches/modules-2/files-2.1')
worker=next(p for p in Path('/home/user/.gradle/.tmp').glob('gradle-worker-classpath*txt') if 'robolectric' in p.read_text())
cp=worker.read_text().splitlines()[1].split(':')
deps=[x for x in cp if '/data/repos/houche/app/build/' not in x]
oldr=base/'app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar'
android='/data/tools/android-sdk/platforms/android-35/android.jar'
classes=tmp/'resource-classes';main=tmp/'main';tests=tmp/'tests'
for p in [classes,main,tests]:p.mkdir(exist_ok=True)
def run(args,log):
    with (tmp/log).open('w') as f:r=subprocess.run(list(map(str,args)),stdout=f,stderr=subprocess.STDOUT,cwd=base/'app')
    print(log,r.returncode,flush=True)
    if r.returncode:print((tmp/log).read_text()[-7000:]);raise SystemExit(r.returncode)
def jar(group,artifact,version=None):
    p=cache/group/artifact
    return next((p/version if version else p).rglob('*.jar'))
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21'),jar('org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),jar('org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),jar('org.jetbrains.kotlin','kotlin-reflect'),jar('org.jetbrains.intellij.deps','trove4j'),jar('org.jetbrains','annotations'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm')]
plugins=[jar('org.jetbrains.kotlin','kotlin-compose-compiler-plugin-embeddable','2.0.21'),jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.0.21')]
maincp=[str(classes),android,str(oldr)]+[x for x in deps if 'mockable-android' not in x]
common=[java,'-Xmx2g','-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17']
stage=sys.argv[1] if len(sys.argv)>1 else 'all'
if stage in ['all','compile']:
    aapt='/data/tools/android-sdk/build-tools/35.0.0/aapt2'
    run([aapt,'compile','--dir',base/'app/src/main/res','-o',tmp/'res.zip'],'resources-compile.log')
    generated=tmp/'java';generated.mkdir(exist_ok=True)
    oldapk=base/'app/build/intermediates/apk_for_local_test/debugUnitTest/packageDebugUnitTestForUnitTest/apk-for-local-test.ap_'
    run([aapt,'link','-I',android,'-I',oldapk,'--manifest',base/'app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml','--auto-add-overlay','--java',generated,'--custom-package','com.yll682.houche','-A',base/'app/build/intermediates/assets/debug/mergeDebugAssets','-o',tmp/'resources.ap_','-R',tmp/'res.zip'],'resources-link.log')
    run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','-d',classes,*generated.rglob('*.java'),base/'app/build/generated/source/buildConfig/debug/com/yll682/houche/BuildConfig.java'],'resources-javac.log')
    run(common+['-classpath',':'.join(maincp),'-d',main]+['-Xplugin='+str(x) for x in plugins]+list((base/'app/src/main/java').rglob('*.kt')),'compile-main.log')
    run(common+['-classpath',':'.join([str(main)]+maincp),'-d',tests,'-Xplugin='+str(plugins[0]),'-Xfriend-paths='+str(main)]+list((base/'app/src/test/java').rglob('*.kt')),'compile-tests.log')
if stage == 'compile-tests':
    run(common+['-classpath',':'.join([str(main)]+maincp),'-d',tests,'-Xplugin='+str(plugins[0]),'-Xfriend-paths='+str(main)]+list((base/'app/src/test/java').rglob('*.kt')),'compile-tests.log')
if stage in ['all','test']:
    config=tmp/'test-resources/com/android/tools';config.mkdir(parents=True,exist_ok=True)
    config.joinpath('test_config.properties').write_text('android_custom_package=com.yll682.houche\nandroid_merged_assets='+str(base/'app/build/intermediates/assets/debug/mergeDebugAssets')+'\nandroid_merged_manifest='+str(base/'app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml')+'\nandroid_resource_apk='+str(tmp/'resources.ap_')+'\n')
    runtime=[str(tmp/'test-resources'),str(tests),str(main),str(classes),str(oldr)]+deps
    (tmp/'junit-classpath.txt').write_text(':'.join(runtime))
    robo=tmp/'robolectric';robo.mkdir(exist_ok=True)
    for f in Path('/home/user/.m2/repository/org/robolectric/android-all-instrumented').rglob('*.jar'):
        target=robo/f.name
        if not target.exists():target.symlink_to(f)
    run([java,'-Xmx2g','-Drobolectric.offline=true','-Drobolectric.dependency.dir='+str(robo),'-Dscreenshots.dir='+str(base/'docs/screenshots'),'-cp',':'.join(runtime),'org.junit.runner.JUnitCore','com.yll682.houche.screenshot.ScreenshotTest','com.yll682.houche.RepositoryTest','com.yll682.houche.SigningTest','com.yll682.houche.LaunchSmokeTest','com.yll682.houche.LiveApiTest'],'junit.log')
if stage=='lint':
    model=tmp/'lint-model';shutil.copytree(base/'app/build/intermediates/lint_report_lint_model/debug/generateDebugLintReportModel',model,dirs_exist_ok=True)
    p=model/'debug.xml';s=p.read_text().replace('partialResultsDir="build/intermediates/lint_partial_results/debug/lintAnalyzeDebug/out"','partialResultsDir="/tmp/houche-check/lint-partial"').replace('classOutputs="build/intermediates/javac/debug/compileDebugJavaWithJavac/classes:build/tmp/kotlin-classes/debug:build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"','classOutputs="/tmp/houche-check/main:/tmp/houche-check/resource-classes"');p.write_text(s)
    jars=list(cache.rglob('*.jar'))
    lintcp=':'.join(str(p) for p in jars if not any(x in str(p) for x in ['kotlin-compiler-embeddable/2.0.21','kotlin-reflect/2.0.21']))
    run([java,'-Xmx2g','-Duser.home=/tmp/houche-check','-cp',lintcp,'com.android.tools.lint.Main','--lint-model',model,'--variant','debug','--sdk-home','/data/tools/android-sdk','--exitcode','--text',tmp/'lint.txt','--xml',tmp/'lint.xml'],'lint.log')
