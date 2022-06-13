package com.lgd.androidpcmtoaacpushing.Audio;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import androidx.annotation.RequiresApi;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by liuguodong on 17/03/18.
 *
 * 音视频编码，对视频进行AVC编码、对音频进行AAC编码
 */

public class AVEncoder {
    private static final String TAG = "AVEncoder";
    public static boolean DEBUG = true;
    //////////////////VIDEO////////////////////////////
    // parameters for the encoder
    private static final String VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video
    // I-frames
    private static final int IFRAME_INTERVAL = 5; // 10 between
    //保存sps帧
    private byte[] mSpsNalu;
    //保存pps帧
    private byte[] mPpsNalu;
    //转成后的数据
    private byte[] yuv420;
    //旋转后的数据
    private byte[] rotateYuv420;
    private int mWidth;
    private int mHeight;
    private int mFps;
    private MediaCodec vEncoder;
    private MediaFormat videoFormat;
    private int mColorFormat;
    private MediaCodec.BufferInfo vBufferInfo;
    private Thread videoEncoderThread;
    private volatile boolean videoEncoderLoop = false;
    private volatile boolean vEncoderEnd = false;
    private LinkedBlockingQueue<byte[]> videoQueue;

    ///////////////////AUDIO/////////////////////////////////
    // parameters for the encoder
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private MediaCodec aEncoder;                // API >= 16(Android4.1.2)
    private MediaCodec.BufferInfo aBufferInfo;        // API >= 16(Android4.1.2)
    private MediaCodecInfo audioCodecInfo;
    private MediaFormat audioFormat;
    private Thread audioEncoderThread;
    private volatile boolean audioEncoderLoop = false;
    private volatile boolean aEncoderEnd = false;
    private LinkedBlockingQueue<byte[]> audioQueue;

    /*
    * 直播流的时间戳不论音频还是视频，在整体时间线上应当呈现递增趋势。如果时间戳计算方法是按照音视频分开计算，那么音频时戳和视频时戳可能并不是在一条时间线上，
    * 这就有可能出现音频时戳在某一个时间点比对应的视频时戳小， 在某一个时间点又跳变到比对应的视频时戳大，导致播放端无法对齐。
    * 目前采用的时间戳以发送视频SPS帧为基础，不区分音频流还是视频流，统一使用即将发送RTMP包的系统时间作为该包的时间戳。
    */
    private long presentationTimeUs;
    private final int TIMEOUT_USEC = 10000;
    private Callback mCallback;

    public static AVEncoder newInstance() {
        return new AVEncoder();
    }

    private AVEncoder() {

    }

    /**
     * 设置回调
     *
     * @param callback 回调
     */
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    public interface Callback {
//        void outputVideoSpsPps(final byte[] sps, final int spslen, final byte[] pps, final int ppslen, final int nTimeStamp);
//        void outputVideoFrame(final byte[] nalu, final int len, final int nTimeStamp);
        void outputAudioSpecConfig(final byte[] aacSpec, final int len);
        void outputAudioData(final byte[] aac, final int len, final int nTimeStamp);
    }



    public void initAudioEncoder(int sampleRate, int pcmFormat, int chanelCount){
        aBufferInfo = new MediaCodec.BufferInfo();
        audioQueue = new LinkedBlockingQueue<>();
        audioCodecInfo = selectCodec(AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            if (DEBUG) Log.e(TAG, "= =lgd= Unable to find an appropriate codec for " + AUDIO_MIME_TYPE);
            return;
        }
        Log.d(TAG, "===liuguodong===selected codec: " + audioCodecInfo.getName());
        audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, sampleRate, chanelCount);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO);//CHANNEL_IN_STEREO 立体声
        int bitRate = sampleRate * pcmFormat * chanelCount;
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, chanelCount);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        Log.d(TAG, " =lgd= =====format: " + audioFormat.toString());

        if (aEncoder != null) {
            return;
        }
        try {
            aEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("===liuguodong===初始化音频编码器失败", e);
        }
        Log.d(TAG, String.format("= =lgd= =编码器:%s创建完成", aEncoder.getName()));
       // aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
//    public void initVideoEncoder(int width, int height, int fps) {
//        this.mWidth = width;
//        this.mHeight = height;
//        mFps = fps;
//        videoQueue = new LinkedBlockingQueue<>();
////        rotateYuv420 = new byte[this.mWidth * this.mHeight * 3 / 2];
////        yuv420 = new byte[this.mWidth * this.mHeight * 3 / 2];
//        yuv420 = new byte[getYuvBuffer(mWidth, mHeight)];
//        rotateYuv420 = new byte[getYuvBuffer(mWidth, mHeight)];
//        Log.d(TAG, "===liuguodong===initVideoEncoder====width: "+mWidth+"  height: "+mHeight+"  bufferSize: "+getYuvBuffer(mWidth, mHeight));
//        vBufferInfo = new MediaCodec.BufferInfo();
//        //选择系统用于编码H264的编码器信息
//        MediaCodecInfo vCodecInfo = selectCodec(VIDEO_MIME_TYPE);
//        if (vCodecInfo == null) {
//            Log.e(TAG, " =lgd= =Unable to find an appropriate codec for " + VIDEO_MIME_TYPE);
//            return;
//        }
//
//        Log.d(TAG, "== =lgd= found video codec: " + vCodecInfo.getName());
//        //根据MIME格式,选择颜色格式
//        mColorFormat = selectColorFormat(vCodecInfo, VIDEO_MIME_TYPE);
//
//        Log.d(TAG, "= =lgd= found colorFormat: " + mColorFormat);
//        //根据MIME创建MediaFormat
//        // sensor出来的是逆时针旋转90度的数据，hal层没有做旋转导致APP显示和编码需要自己做顺时针旋转90,这样看到的图像才是正常的
//        videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE,
//                this.mHeight, this.mWidth);
//        int bitrate = (mWidth * mHeight * 3 / 2) * 8 * fps;
//        //设置比特率,将编码比特率值设为bitrate
//        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
//        //设置帧率,将编码帧率设为Camera实际帧率mFps
//        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
//        //设置颜色格式
//        videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, mColorFormat);
//        //设置关键帧的时间
//        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
//
//        Log.d(TAG, "= =lgd= =video==format: " + videoFormat.toString());
//
//        if (vEncoder != null) {
//            return;
//        }
//        try {
//            //创建一个MediaCodec
//            vEncoder = MediaCodec.createByCodecName(vCodecInfo.getName());
//        } catch (IOException e) {
//            e.printStackTrace();
//            throw new RuntimeException("===liuguodong===初始化视频编码器失败", e);
//        }
//        Log.d(TAG, String.format("= =lgd= =编码器:%s创建完成", vEncoder.getName()));
//    }

    private int selectColorFormat(MediaCodecInfo codecInfo,
                                  String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo
                .getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }

        Log.d(TAG,
                "==liuguodong====couldn't find a good color format for " + codecInfo.getName()
                        + " / " + mimeType);
        return 0; // not reached
    }

    private boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    private MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    /**
     * 开始
     */
    public void start() {
        startAudioEncode();
//        startVideoEncode();
    }

    /**
     * 停止
     */
    public void stop() {
        stopAudioEncode();
        stopVideoEncode();
    }

//    private void startVideoEncode(){
//        if (vEncoder == null) {
//            throw new RuntimeException(" =lgd= =请初始化视频编码器=====");
//        }
//
//        if (videoEncoderLoop) {
//            throw new RuntimeException(" =lgd= 视频编码必须先停止===");
//        }
//
//        videoEncoderThread = new Thread() {
//            @Override
//            public void run() {
//                Log.d(TAG, "===liuguodong=====Video 编码线程 启动...");
//                presentationTimeUs = System.currentTimeMillis() * 1000;
//                vEncoderEnd = false;
//                vEncoder.configure(videoFormat, null, null,
//                        MediaCodec.CONFIGURE_FLAG_ENCODE);
//                vEncoder.start();
//                while (videoEncoderLoop && !Thread.interrupted()) {
//                    try {
//                        byte[] data = videoQueue.take(); //待编码的数据
//                        if (DEBUG) Log.d(TAG, "== =lgd= 要编码的Video数据大小:" + data.length);
//                        encodeVideoData(data);
//                    } catch (InterruptedException e) {
//                        Log.e(TAG, "===liuguodong==========编码(Video)数据 失败");
//                        e.printStackTrace();
//                        break;
//                    }
//                }
//
//                if (vEncoder != null) {
//                    //停止视频编码器
//                    vEncoder.stop();
//                    //释放视频编码器
//                    vEncoder.release();
//                    vEncoder = null;
//                }
//                mSpsNalu = null;
//                mPpsNalu = null;
//                videoQueue.clear();
//                Log.d(TAG, "= =lgd= ==Video 编码线程 退出...");
//            }
//        };
//        videoEncoderLoop = true;
//        videoEncoderThread.start();
//    }

    private void stopVideoEncode() {
        Log.d(TAG, "== =lgd= ==stop video 编码...");
        vEncoderEnd = true;
    }

    private void startAudioEncode() {
        if (aEncoder == null) {
            throw new RuntimeException(" =lgd= =请初始化音频编码器=====");
        }

        if (audioEncoderLoop) {
            throw new RuntimeException(" =lgd= 音频编码线程必须先停止===");
        }
        audioEncoderThread = new Thread() {
            @Override
            public void run() {
                Log.d(TAG, "===liuguodong=====Audio 编码线程 启动...");
                presentationTimeUs = System.currentTimeMillis() * 1000;
                aEncoderEnd = false;
                aEncoder.configure(audioFormat, null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
                aEncoder.start();
                while (audioEncoderLoop && !Thread.interrupted()) {
                    try {
                        byte[] data = audioQueue.take();
                        encodeAudioData(data);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
                if (aEncoder != null) {
                    //停止音频编码器
                    aEncoder.stop();
                    //释放音频编码器
                    aEncoder.release();
                    aEncoder = null;
                }

                audioQueue.clear();
                Log.d(TAG, "= =lgd= ==Audio 编码线程 退出...");
            }
        };
        audioEncoderLoop = true;
        audioEncoderThread.start();
    }

    private void stopAudioEncode() {
        Log.d(TAG, "== =lgd= ==stop Audio 编码...");
        aEncoderEnd = true;
    }

    /**
     * 添加视频数据
     *
     * @param data
     */
    public void putVideoData(byte[] data) {
        try {
            videoQueue.put(data);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 添加音频数据
     *
     * @param data
     */
    public void putAudioData(byte[] data) {
        try {
            audioQueue.put(data);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private int getYuvBuffer(int width, int height) {
        int yStride = (int) Math.ceil(width / 16.0) * 16;
        int uvStride = (int) Math.ceil( (yStride / 2) / 16.0) * 16;
        int ySize = yStride * height;
        int uvSize = uvStride * height / 2;
        return ySize + uvSize * 2;
    }

//    private void encodeVideoData(byte[] input) {
//        if(mColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar){
//            //nv21格式转为nv12格式
//            Yuv420POperate.NV21ToNV12(input,yuv420,mWidth,mHeight);
//        }else if(mColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar){
//            //用于NV21格式转换为YUV420P格式
//            Yuv420POperate.NV21toYUV420P(input, yuv420, mWidth, mHeight);
//        }
//        Yuv420POperate.YUV420PClockRot90(rotateYuv420, yuv420, mWidth, mHeight);
//        try {
//            //拿到输入缓冲区,用于传送数据进行编码
//            ByteBuffer[] inputBuffers = vEncoder.getInputBuffers();
//            //得到当前有效的输入缓冲区的索引
//            int inputBufferIndex = vEncoder.dequeueInputBuffer(TIMEOUT_USEC);
//            Log.d(TAG, "==1 =lgd= =Video===inputBufferIndex: " + inputBufferIndex+"  yuvLen: "+rotateYuv420.length);
//            if (inputBufferIndex >= 0) { //输入缓冲区有效
//                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
//                inputBuffer.clear();
//                Log.d(TAG, "===2===liuguodong=====Video===inputBufferIndex: " + inputBufferIndex+"  capacity: "+inputBuffer.capacity());
//                //往输入缓冲区写入数据
//                inputBuffer.put(rotateYuv420);
//                Log.d(TAG, "===3===liuguodong=====Video===inputBufferIndex: " + inputBufferIndex+"  capacity: "+inputBuffer.capacity()+"  limit: "+inputBuffer.limit());
//
//                //计算pts，这个值是一定要设置的
//               // long pts = new Date().getTime() * 1000 - presentationTimeUs;
//                long pts = System.currentTimeMillis() * 1000 -  presentationTimeUs;
//                if (vEncoderEnd) {
//                    //结束时，发送结束标志，在编码完成后结束
//                    Log.d(TAG, "=====liuguodong===send Video Encoder BUFFER_FLAG_END_OF_STREAM====");
//                    vEncoder.queueInputBuffer(inputBufferIndex, 0, rotateYuv420.length,
//                            pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
//                } else {
//                    //将缓冲区入队
//                    Log.d(TAG, "=====liuguodong===Video====inputBufferIndex: "+inputBufferIndex+"  pts: "+pts);
//                    vEncoder.queueInputBuffer(inputBufferIndex, 0, rotateYuv420.length,
//                            pts, 0);
//                }
//            }
//
//            //拿到输出缓冲区,用于取到编码后的数据
//            ByteBuffer[] outputBuffers = vEncoder.getOutputBuffers();
//            //拿到输出缓冲区的索引
//            int outputBufferIndex = vEncoder.dequeueOutputBuffer(vBufferInfo, TIMEOUT_USEC);
//            while (outputBufferIndex >= 0) {
//                Log.d(TAG, "= =lgd= Video===outputBufferIndex: " + outputBufferIndex);
//                //数据已经编码成H264格式
//                //outputBuffer保存的就是H264数据
//                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
//                if (outputBuffer == null) {
//                    throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex +
//                            " was null");
//                }
//
//                if (vBufferInfo.size != 0) {
//                    byte[] outData = new byte[vBufferInfo.size];
//                    outputBuffer.get(outData);
//
//                    // 0 0 0 1 103 66 -128 41 -38 15 10 104 6 -48 -95 53  0 0 0 1 104 -50 6 -30
//                    //sps序列参数集，即0x67 pps图像参数集，即0x68，MediaCodec编码输出的头两个NALU即为sps和pps
//                    //并且在h264码流的开始两帧即为sps和pps，在这里MediaCodec将sps和pps作为一个buffer输出。
//                    if (mSpsNalu != null && mPpsNalu != null) {
//                        int naluType = outData[4] & 0x1f;
//                        Log.d(TAG, "===liuguodong===AVC Frame===data: " + outData[0] + " ," + outData[1] + " ," + outData[2] + " ," + outData[3] + "  ," + outData[4] + "  len: " + outData.length + "    AVC帧类型: " + naluType + "   时间戳: " + vBufferInfo.presentationTimeUs / 1000);
//
//                        if (naluType == 0x05 || naluType == 0x01) {//IDR SLICE
////                            if (null != mCallback) {
////                                Log.d(TAG, "= =lgd= =SPS PPS帧=====时间戳: " + vBufferInfo.presentationTimeUs / 1000);
////                                mCallback.outputVideoSpsPps(mSpsNalu, mSpsNalu.length, mPpsNalu, mPpsNalu.length, (int) (vBufferInfo.presentationTimeUs / 1000));
////                            }
//                        }
//                        if (null != mCallback && !vEncoderEnd) {
//                            mCallback.outputVideoFrame(outData, outData.length, (int) (vBufferInfo.presentationTimeUs / 1000));
//                        }
//                    } else {
//                        //保存pps sps 即h264码流开始两帧，保存起来后面用
//                        ByteBuffer spsPpsBuffer = ByteBuffer.wrap(outData);
//                        if (spsPpsBuffer.getInt() == 0x00000001 && (spsPpsBuffer.get(4) == 0x67)) {
//                            //通过上面的打印看到sps帧长度为输出buffer的前面12字节
//                            mSpsNalu = new byte[outData.length - 4 - 8]; //8为两个startCode的长度，一个startCode为0x00000001
//                            //通过上面的打印看到pps帧长度为输出buffer的最后4字节
//                            mPpsNalu = new byte[4];
//                            //保存sps帧
//                            spsPpsBuffer.get(mSpsNalu, 0, mSpsNalu.length);
//                            //跳过startCode 0x00000001
//                            spsPpsBuffer.getInt();
//                            //保存pps帧
//                            spsPpsBuffer.get(mPpsNalu, 0, mPpsNalu.length);
//                            Log.d(TAG, "= =lgd= =1==sps==pps==:" + outData.length);
//                            for (int i = 0; i < outData.length; i++) {
//                                Log.d(TAG, "=====liuguodong===2==sps==pps==:" + outData[i]);
//                            }
//                            Log.d(TAG, "= =lgd= 3==sps==pps==:" + outData.length);
//                            if (null != mCallback && !vEncoderEnd) {
//                                Log.d(TAG, "= =lgd= =SPS PPS帧=====时间戳: " + vBufferInfo.presentationTimeUs / 1000);
//                                mCallback.outputVideoSpsPps(mSpsNalu, mSpsNalu.length, mPpsNalu, mPpsNalu.length, (int) (vBufferInfo.presentationTimeUs / 1000));
//                            }
//                        }
//                    }
//                }
//                //释放资源
//                vEncoder.releaseOutputBuffer(outputBufferIndex, false);
//                //拿到输出缓冲区的索引
//                outputBufferIndex = vEncoder.dequeueOutputBuffer(vBufferInfo, 0);
//                //编码结束的标志
//                if ((vBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    Log.d(TAG, "= =lgd= =Recv Video Encoder===BUFFER_FLAG_END_OF_STREAM=====" );
//                    videoEncoderLoop = false;
//                    videoEncoderThread.interrupt();
//                    return;
//                }
//            }
//        } catch (Exception t) {
//            Log.e(TAG, " =lgd= =encodeVideoData=====error: " + t.toString());
//        }
//    }

    private void encodeAudioData(byte[] input){
        try {
            //拿到输入缓冲区,用于传送数据进行编码
            ByteBuffer[] inputBuffers = aEncoder.getInputBuffers();
            //得到当前有效的输入缓冲区的索引
            int inputBufferIndex = aEncoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) { //输入缓冲区有效
                if (DEBUG) Log.d(TAG, "== =lgd= Audio===inputBufferIndex: " + inputBufferIndex);
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                //往输入缓冲区写入数据
                inputBuffer.put(input);

                //计算pts，这个值是一定要设置的
                long pts = new Date().getTime() * 1000 - presentationTimeUs;
                if (aEncoderEnd) {
                    //结束时，发送结束标志，在编码完成后结束
                    Log.d(TAG, "=====liuguodong===send Audio Encoder BUFFER_FLAG_END_OF_STREAM====");
                    aEncoder.queueInputBuffer(inputBufferIndex, 0, input.length,
                            pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    //将缓冲区入队
                    aEncoder.queueInputBuffer(inputBufferIndex, 0, input.length,
                            pts, 0);
                }
            }

            //拿到输出缓冲区,用于取到编码后的数据
            ByteBuffer[] outputBuffers = aEncoder.getOutputBuffers();
            //拿到输出缓冲区的索引
            int outputBufferIndex = aEncoder.dequeueOutputBuffer(aBufferInfo, TIMEOUT_USEC);
            while (outputBufferIndex >= 0) {
                Log.d(TAG, "= =lgd= Audio===outputBufferIndex: " + outputBufferIndex);
                //数据已经编码成AAC格式
                //outputBuffer保存的就是AAC数据
                ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                if (outputBuffer == null) {
                    throw new RuntimeException("encoderOutputBuffer " + outputBufferIndex +
                            " was null");
                }

                if (aBufferInfo.size != 0) {
                    // byte[] outData = new byte[mBufferInfo.size];
                    // outputBuffer.get(outData);
                    onEncodeAacFrame(outputBuffer,aBufferInfo);
                }
                //释放资源
                aEncoder.releaseOutputBuffer(outputBufferIndex, false);
                //拿到输出缓冲区的索引
                outputBufferIndex = aEncoder.dequeueOutputBuffer(aBufferInfo, 0);
                //编码结束的标志
                if ((aBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.e(TAG, "= =lgd= =Recv Audio Encoder===BUFFER_FLAG_END_OF_STREAM=====" );
                    audioEncoderLoop = false;
                    audioEncoderThread.interrupt();
                    return;
                }
            }
        } catch (Exception t) {
            Log.e(TAG, "= =lgd= =encodeAudioData=====error: " + t.toString());
        }
    }

    private void onEncodeAacFrame(ByteBuffer bb, MediaCodec.BufferInfo aBufferInfo) {
        if (aBufferInfo.size == 2) {
            byte[] bytes = new byte[2];
            bb.get(bytes);
            Log.d(TAG, "== =lgd= bytes[0]: " + bytes[0] + "    bytes[1]: " + bytes[1]);
            if (null != mCallback) {
                mCallback.outputAudioSpecConfig(bytes, 2);
            }
        } else {
            byte[] bytes = new byte[aBufferInfo.size];
            bb.get(bytes);
            if (null != mCallback) {
                mCallback.outputAudioData(bytes, bytes.length, (int) aBufferInfo.presentationTimeUs / 1000);
            }
        }
    }
}
