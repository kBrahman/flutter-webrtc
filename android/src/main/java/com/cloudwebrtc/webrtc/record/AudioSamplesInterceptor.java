package com.cloudwebrtc.webrtc.record;

import android.annotation.SuppressLint;

import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioSamples;
import android.util.Log;

import java.util.HashMap;

/** JavaAudioDeviceModule allows attaching samples callback only on building
 *  We don't want to instantiate VideoFileRenderer and codecs at this step
 *  It's simple dummy class, it does nothing until samples are necessary */
@SuppressWarnings("WeakerAccess")
public class AudioSamplesInterceptor implements SamplesReadyCallback {
    private final static String TAG="AudioSamplesInterceptor";
    @SuppressLint("UseSparseArrays")
    protected final HashMap<Integer, SamplesReadyCallback> callbacks = new HashMap<>();

    @Override
    public void onWebRtcAudioRecordSamplesReady(AudioSamples audioSamples) {
        Log.i(TAG,"onWebRtcAudioRecordSamplesReady");
        for (SamplesReadyCallback callback : callbacks.values()) {
            callback.onWebRtcAudioRecordSamplesReady(audioSamples);
        }
    }

    public void attachCallback(Integer id, SamplesReadyCallback callback) throws Exception {
        callbacks.put(id, callback);
    }

    public void detachCallback(Integer id) {
        callbacks.remove(id);
    }

}
