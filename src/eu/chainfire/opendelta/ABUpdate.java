/*
 * Copyright (C) 2017 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.chainfire.opendelta;

import android.os.PowerManager.WakeLock;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import eu.chainfire.opendelta.UpdateService.ProgressListener;

class ABUpdate {

    private static final String TAG = "ABUpdateInstaller";

    private static final String PAYLOAD_BIN_PATH = "payload.bin";
    private static final String PAYLOAD_PROPERTIES_PATH = "payload_properties.txt";

    private static final String PREFS_IS_INSTALLING_UPDATE = "prefs_is_installing_update";

    private final String zipPath;
    private final boolean enableABPerfMode;

    private final ProgressListener mProgressListener;

    private final UpdateService mUpdateService;
    private final UpdateEngine mUpdateEngine;
    private boolean mBound;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {
        @Override
        public void onStatusUpdate(int status, float percent) {
            Logger.d("onStatusUpdate = " + status);
            // downloading stage: 0% - 30%
            int offset = 0;
            int weight = 30;

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT:
                    setInstallingUpdate(false, mUpdateService);
                    mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, -1);
                    return;
                case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT:
                    setInstallingUpdate(false, mUpdateService);
                    mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.ERROR, -1);
                    return;
                case UpdateEngine.UpdateStatusConstants.VERIFYING:
                    // verifying stage: 30% - 35%
                    offset = 30;
                    weight = 5;
                    break;
                case UpdateEngine.UpdateStatusConstants.FINALIZING:
                    // finalizing stage: 35% - 100%
                    offset = 35;
                    weight = 65;
                    break;
            }

            if (mProgressListener != null) {
                mProgressListener.setStatus(mUpdateService.getString(mUpdateService.getResources().getIdentifier(
                    "progress_status_" + status, "string", mUpdateService.getPackageName())));
                mProgressListener.onProgress(percent * (float) weight + (float) offset,
                    (long) Math.round(percent * weight) + (long) offset, 100L);
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            Logger.d("onPayloadApplicationComplete = " + errorCode);
            setInstallingUpdate(false, mUpdateService);
            if (errorCode == UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE) {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, errorCode);
            } else if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.ERROR, errorCode);
            } else {
                mUpdateService.onUpdateCompleted(UpdateEngine.ErrorCodeConstants.SUCCESS, -1);
            }
        }
    };

    static synchronized boolean start(String zipPath, ProgressListener listener,
            UpdateService us) {
        if (isInstallingUpdate(us)) {
            return false;
        }
        ABUpdate installer = new ABUpdate(zipPath, listener, us);
        setInstallingUpdate(installer.startUpdate(), us);
        return isInstallingUpdate(us);
    }

    static synchronized boolean resume(String zipPath, ProgressListener listener,
            UpdateService us) {
        ABUpdate installer = new ABUpdate(zipPath, listener, us);
        setInstallingUpdate(installer.bindCallbacks(), us);
        return isInstallingUpdate(us);
    }

    static synchronized boolean isInstallingUpdate(UpdateService us) {
        return us.getPrefs()
                .getBoolean(PREFS_IS_INSTALLING_UPDATE, false);
    }

    static synchronized void setInstallingUpdate(boolean installing, UpdateService us) {
        final boolean enabled = us.getConfig().getABWakeLockCurrent();
        final WakeLock wakeLock = us.getWakeLock();
        if (installing && enabled) wakeLock.acquire();
        else if (wakeLock.isHeld()) wakeLock.release();

        us.getPrefs().edit()
                .putBoolean(PREFS_IS_INSTALLING_UPDATE, installing).commit();
    }

    static synchronized void pokeStatus(String zipPath, UpdateService us) {
        ABUpdate installer = new ABUpdate(zipPath, null, us);
        installer.bindCallbacks();
    }

    private ABUpdate(String zipPath, ProgressListener listener,
            UpdateService us) {
        this.zipPath = zipPath;
        this.mProgressListener = listener;
        this.mUpdateService = us;
        this.enableABPerfMode = mUpdateService.getConfig().getABPerfModeCurrent();
        this.mUpdateEngine = new UpdateEngine();
    }

    private boolean bindCallbacks() {
        if (mBound) return true;
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind UpdateEngineCallback");
            return false;
        }
        return true;
    }

    private boolean startUpdate() {
        File file = new File(zipPath);
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist");
            return false;
        }

        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipFile zipFile = new ZipFile(file);
            offset = getZipEntryOffset(zipFile, PAYLOAD_BIN_PATH);
            ZipEntry payloadPropEntry = zipFile.getEntry(PAYLOAD_PROPERTIES_PATH);
            try (InputStream is = zipFile.getInputStream(payloadPropEntry);
                 InputStreamReader isr = new InputStreamReader(is);
                 BufferedReader br = new BufferedReader(isr)) {
                List<String> lines = new ArrayList<>();
                for (String line; (line = br.readLine()) != null;) {
                    lines.add(line);
                }
                headerKeyValuePairs = new String[lines.size()];
                headerKeyValuePairs = lines.toArray(headerKeyValuePairs);
            }
            zipFile.close();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + file, e);
            return false;
        }

        mUpdateEngine.setPerformanceMode(enableABPerfMode);
        if (!bindCallbacks()) return false;
        String zipFileUri = "file://" + file.getAbsolutePath();
        mUpdateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs);

        return true;
    }

    static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(PAYLOAD_PROPERTIES_PATH) != null;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IOException for IO errors
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath)
            throws IOException {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }
}
