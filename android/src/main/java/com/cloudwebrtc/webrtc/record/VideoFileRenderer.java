package com.cloudwebrtc.webrtc.record;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.cloudwebrtc.webrtc.FlutterWebRTCPlugin;

import org.webrtc.EglBase;
import org.webrtc.GlRectDrawer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoFrameDrawer;
import org.webrtc.VideoSink;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

class VideoFileRenderer extends MediaCodec.Callback implements VideoSink {
    private static final String TAG = "VideoFileRenderer";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BIT_RATE = 64000;
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;
    private static final int HOUR_IN_SEC = 3600_000;
    private final List<BufferData> videoBuffers = new LinkedList<>();
    private final HandlerThread renderThread;
    private final Handler renderThreadHandler;
    private final String outputPath;
    private final FlutterWebRTCPlugin.RecEvent recEvent;
    private long lastTs;
    private int outputFileWidth = -1;
    private int outputFileHeight = -1;
    private ByteBuffer[] videoOutputBuffers;
    private boolean encoderStarted = false;
    private long videoFrameStart = 0;
    private long audioFrameStart = 0;
    private EglBase eglBase;
    private final EglBase.Context sharedContext;
    private VideoFrameDrawer frameDrawer;
    private MediaMuxer mediaMuxer;
    private MediaCodec audioEncoder;
    private MediaCodec videoEncoder;
    private final MediaCodec.BufferInfo videoBufferInfo;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private GlRectDrawer drawer;
    private Surface surface;
    private AudioRecord audioRecord;
    private boolean isRecording = true;
    private long startRec = System.currentTimeMillis();

    VideoFileRenderer(String outputPath, final EglBase.Context sharedContext, FlutterWebRTCPlugin.RecEvent recEvent) {
        this.outputPath = outputPath;
        renderThread = new HandlerThread(TAG + "RenderThread");
        renderThread.start();
        renderThreadHandler = new Handler(renderThread.getLooper());
        videoBufferInfo = new MediaCodec.BufferInfo();
        this.sharedContext = sharedContext;
        this.recEvent = recEvent;
    }


    private void initVideoEncoder() {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(MIME_TYPE, outputFileWidth, outputFileHeight);
        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        try {
            videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            renderThreadHandler.post(() -> {
                eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE);
                surface = videoEncoder.createInputSurface();
                eglBase.createSurface(surface);
                eglBase.makeCurrent();
                drawer = new GlRectDrawer();
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public void onFrame(VideoFrame frame) {
        frame.retain();
        if (outputFileWidth == -1) {
            outputFileWidth = frame.getRotatedWidth();
            outputFileHeight = frame.getRotatedHeight();
            initVideoEncoder();
        }
        renderThreadHandler.post(() -> {
            try {
                renderFrameOnRenderThread(frame);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        if (System.currentTimeMillis() - startRec > HOUR_IN_SEC) restartRec();
        else if ((System.currentTimeMillis() - startRec) % 30000 == 0)
            Log.i(TAG, "recording for:" + (System.currentTimeMillis() - startRec) / 1000 + " secs");
    }

    private void renderFrameOnRenderThread(VideoFrame frame) throws IOException {
        if (frameDrawer == null) frameDrawer = new VideoFrameDrawer();
        frameDrawer.drawFrame(frame, drawer, null, 0, 0, outputFileWidth, outputFileHeight);
        frame.release();
        drainEncoders();
        eglBase.swapBuffers();
    }


    private void restartRec() {
        Log.i(TAG, "restart rec");
        recEvent.onRestartRec();
        startRec = System.currentTimeMillis();
        Log.i(TAG, "start next rec:" + new Date(startRec));
    }

    /**
     * Release all resources. All already posted frames will be rendered first.
     */
    void release() {
        Log.i(TAG, "release, audio rec:" + audioRecord);
        isRecording = false;
        renderThreadHandler.post(() -> {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            } else new File(outputPath).delete();
            videoEncoder.stop();
            videoEncoder.release();
            eglBase.release();
            renderThread.quit();
        });
    }

    private void drainEncoders() throws IOException {
        if (!encoderStarted) {
            videoEncoder.start();
            videoOutputBuffers = videoEncoder.getOutputBuffers();
            encoderStarted = true;
            return;
        }

        while (isRecording) {
            int videoStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, 10000);
            if (videoStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (videoStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                videoOutputBuffers = videoEncoder.getOutputBuffers();
                Log.e(TAG, "encoder output buffers changed");
            } else if (videoStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && videoTrackIndex == -1) {
                mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                videoTrackIndex = mediaMuxer.addTrack(videoEncoder.getOutputFormat());
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioEncoder.setCallback(this);
                audioRecord.startRecording();
                audioEncoder.start();
                Log.i(TAG, "video track assigned" + videoTrackIndex);
            } else if (videoStatus < 0) {
                Log.e(TAG, "unexpected result fr om encoder.dequeueOutputBuffer: " + videoStatus);
            } else { // videoStatus >= 0
                try {
                    ByteBuffer videoData = videoOutputBuffers[videoStatus];
                    if (videoData == null) {
                        Log.e(TAG, "encoderOutputBuffer " + videoStatus + " was null");
                        break;
                    }
                    videoData.position(videoBufferInfo.offset);
                    videoData.limit(videoBufferInfo.offset + videoBufferInfo.size);
                    if (videoFrameStart == 0 && videoBufferInfo.presentationTimeUs != 0)
                        videoFrameStart = videoBufferInfo.presentationTimeUs;
                    videoBufferInfo.presentationTimeUs -= videoFrameStart;
                    if (audioTrackIndex == -1) {
                        final MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
                        copy.set(videoBufferInfo.offset, videoBufferInfo.size, videoBufferInfo.presentationTimeUs, videoBufferInfo.flags);
                        ByteBuffer copiedData = ByteBuffer.allocate(videoBufferInfo.size);
                        copiedData.put(videoData);
                        copiedData.flip();
                        videoBuffers.add(new BufferData(copiedData, copy));
                    } else {
                        for (BufferData data : videoBuffers)
                            mediaMuxer.writeSampleData(videoTrackIndex, data.buffer, data.bufferInfo);
                        videoBuffers.clear();
                        mediaMuxer.writeSampleData(videoTrackIndex, videoData, videoBufferInfo);
                    }
                    videoEncoder.releaseOutputBuffer(videoStatus, false);
                    if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "video end of stream");
                        break;
                    }
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                    break;
                }
            }
        }
    }

    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
        if (!isRecording) return;
        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        inputBuffer.clear();
        int readBytes = audioRecord.read(inputBuffer, inputBuffer.capacity());
        if (readBytes > 0)
            codec.queueInputBuffer(index, 0, readBytes, System.nanoTime() / 1000, 0);
    }


    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
        final int size = info.size;
        if (size != 0 && isRecording) {
            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            outputBuffer.position(info.offset);
            outputBuffer.limit(info.offset + info.size);
            if (audioFrameStart == 0 && info.presentationTimeUs != 0) audioFrameStart = info.presentationTimeUs;
            info.presentationTimeUs -= audioFrameStart;
            if (info.presentationTimeUs <= lastTs) info.presentationTimeUs = lastTs + 1;
            lastTs = info.presentationTimeUs;
            mediaMuxer.writeSampleData(audioTrackIndex, outputBuffer, info);
            codec.releaseOutputBuffer(index, false);
        } else if (!isRecording) {
            codec.stop();
            codec.release();
            if (audioTrackIndex == -1 || videoTrackIndex == -1) new File(outputPath).delete();
            else {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        }
    }

    @Override
    public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
        Log.i(TAG, "codec err:" + e.getMessage());
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
        if (audioTrackIndex == -1) {
            audioTrackIndex = mediaMuxer.addTrack(audioEncoder.getOutputFormat());
            mediaMuxer.start();
        }
    }

    private static class BufferData {
        public ByteBuffer buffer;
        public MediaCodec.BufferInfo bufferInfo;

        public BufferData(ByteBuffer buffer, MediaCodec.BufferInfo bufferInfo) {
            this.buffer = buffer;
            this.bufferInfo = new MediaCodec.BufferInfo();
            this.bufferInfo.set(bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);
        }
    }
}
