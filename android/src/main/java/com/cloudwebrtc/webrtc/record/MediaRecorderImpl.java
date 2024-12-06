package com.cloudwebrtc.webrtc.record;

import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin;
import com.cloudwebrtc.webrtc.MethodCallHandlerImpl;
import com.cloudwebrtc.webrtc.utils.EglUtils;

import org.webrtc.VideoTrack;

import java.io.File;

public class MediaRecorderImpl {

    private final VideoTrack videoTrack;
    private final FlutterWebRTCPlugin.RecEvent recEvent;
    private VideoFileRenderer videoFileRenderer;
    private boolean isRunning = false;
    private File recordFile;

    public MediaRecorderImpl(@Nullable VideoTrack videoTrack, FlutterWebRTCPlugin.RecEvent recEvent) {
        this.videoTrack = videoTrack;
        this.recEvent = recEvent;
    }

    public void startRecording(File file) {
        recordFile = file;
        if (isRunning) return;
        isRunning = true;
        file.getParentFile().mkdirs();
        if (videoTrack != null) {
            videoFileRenderer = new VideoFileRenderer(file.getAbsolutePath(), EglUtils.getRootEglBaseContext(), recEvent);
            videoTrack.addSink(videoFileRenderer);
        }
    }

    public File getRecordFile() {
        return recordFile;
    }

    public void stopRecording() {
        isRunning = false;
        if (videoTrack != null && videoFileRenderer != null) {
            videoTrack.removeSink(videoFileRenderer);
            videoFileRenderer.release();
            videoFileRenderer = null;
        }
    }

    private static final String TAG = "MediaRecorderImpl";

}
