// 3. LiveVideoService.java
package com.example.localservice;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;

public class LiveVideoService extends Service {
    private static final String TAG = "LiveVideoService";
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    
    private String serverIp = "";
    private static final int VIDEO_PORT = 8888;
    private static final int DISCOVERY_PORT = 8889;
    
    private Socket socket;
    private DataOutputStream outputStream;
    private volatile boolean isRunning = true;

    @Override
    public void onCreate() {
        super.onCreate();
        startBackgroundThread();
        new Thread(this::discoverServerIP).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraVideoBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void discoverServerIP() {
        try (DatagramSocket udpSocket = new DatagramSocket(DISCOVERY_PORT)) {
            udpSocket.setBroadcast(true);
            byte[] buffer = new byte[256];
            while (isRunning && (serverIp == null || serverIp.isEmpty())) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                if (message.startsWith("ENI_PC_SERVER:")) {
                    serverIp = message.split(":")[1];
                    Log.d(TAG, "Discovered PC IP: " + serverIp);
                    connectAndStream();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Discovery error: " + e.getMessage());
        }
    }

    private void connectAndStream() {
        try {
            socket = new Socket(serverIp, VIDEO_PORT);
            outputStream = new DataOutputStream(socket.getOutputStream());
            Log.d(TAG, "Connected to video stream receiver on " + serverIp);
            backgroundHandler.post(this::openCamera);
        } catch (Exception e) {
            Log.e(TAG, "Stream connection failed: " + e.getMessage());
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    image.close();
                    sendVideoFrame(bytes);
                }
            }, backgroundHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCameraPreviewSession();
                }
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
                @Override
                public void onError(@NonNull CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Camera error: " + e.getMessage());
        }
    }

    private void createCameraPreviewSession() {
        try {
            final CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        try {
                            builder.set(CaptureRequest.CONTROL_MODE, CameraDevice.TEMPLATE_PREVIEW);
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                }, backgroundHandler);
        } catch (Exception e) { Log.e(TAG, e.getMessage()); }
    }

    private void sendVideoFrame(byte[] jpegData) {
        try {
            if (outputStream != null) {
                outputStream.writeInt(jpegData.length);
                outputStream.write(jpegData);
                outputStream.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Frame send failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (cameraDevice != null) cameraDevice.close();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (backgroundThread != null) backgroundThread.quitSafely();
    }
}