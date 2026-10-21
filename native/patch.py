"""Aplica as alteracoes nativas no projeto Android gerado pelo Capacitor. Pode ser executado varias vezes."""
import re, pathlib
A = pathlib.Path('android')
man = A/'app/src/main/AndroidManifest.xml'
t = man.read_text(encoding='utf-8')
if 'CosiOverlayService' not in t:
    perms = '''
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />
    <uses-permission android:name="android.permission.VIBRATE" />
'''
    service = '''
        <service
            android:name=".CosiOverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Assistente virtual flutuante exibido sobre outros apps" />
        </service>
'''
    t = t.replace('</application>', service + '    </application>', 1).replace('</manifest>', perms + '</manifest>', 1)
    man.write_text(t, encoding='utf-8')
pkg = re.search(r'package="([^"]+)"', t)
main = next((A/'app/src/main/java').rglob('MainActivity.java'))
m = main.read_text(encoding='utf-8')
if 'CosiNativePlugin' not in m:
    m = m.replace('import com.getcapacitor.BridgeActivity;', 'import android.os.Bundle;\nimport com.getcapacitor.BridgeActivity;')
    m = re.sub(r'public class MainActivity extends BridgeActivity\s*\{\s*\}',
               'public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        registerPlugin(CosiNativePlugin.class);\n        super.onCreate(savedInstanceState);\n    }\n}', m)
    main.write_text(m, encoding='utf-8')
g = A/'app/build.gradle'
b = g.read_text(encoding='utf-8')
if 'androidx.biometric' not in b:
    b = b.replace('dependencies {', 'dependencies {\n    implementation "androidx.biometric:biometric:1.1.0"', 1)
    g.write_text(b, encoding='utf-8')
v = A/'variables.gradle'
if v.exists():
    vt = v.read_text(encoding='utf-8')
    v.write_text(re.sub(r'minSdkVersion\s*=\s*\d+', 'minSdkVersion = 24', vt), encoding='utf-8')
print('Alteracoes nativas aplicadas.')
