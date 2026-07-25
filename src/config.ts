import dotenv from 'dotenv';
import path from 'path';

dotenv.config({ path: path.resolve(__dirname, '../.env') });

export const config = {
    botToken: process.env.BOT_TOKEN || '',
    adminId: parseInt(process.env.ADMIN_ID || '0', 10),
    appUrl: process.env.APP_URL || 'http://localhost:8000',
    jwtSecret: process.env.JWT_SECRET || 'default-secret-change-me',
    port: parseInt(process.env.PORT || '8000', 10),
    dbPath: path.resolve(__dirname, '../data/dragon.db'),
    factoryPath: path.resolve(__dirname, '../factory'),
    baseApkPath: path.resolve(__dirname, '../factory/baseApp/base.apk'),
    signerJarPath: path.resolve(__dirname, '../factory/uber-apk-signer.jar'),
    dataDir: path.resolve(__dirname, '../data'),
    buildsDir: path.resolve(__dirname, '../data/builds'),
};
