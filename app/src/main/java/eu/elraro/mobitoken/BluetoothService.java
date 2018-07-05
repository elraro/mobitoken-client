package eu.elraro.mobitoken;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

public class BluetoothService extends Service {

    final int handlerState = 0;                        //used to identify handler message
    Handler bluetoothIn;
    private ConnectingThread mConnectingThread;
    private ConnectedThread mConnectedThread;
    private boolean stopThread;
    private StringBuilder recDataString = new StringBuilder();

    static final int NOTIFICATION_ID = 543;

    public static final String ACTION_CHECK_BLUETOOTH = "eu.elraro.mobitoken.BlueToothService.ACTION_CHECK_BLUETOOTH";
    public static final String ACTION_START_BLUETOOTH = "eu.elraro.mobitoken.BlueToothService.ACTION_START_BLUETOOTH";

    public static boolean isServiceRunning = false;

    private Messenger messageHandler;

    private String[] creds = new String[4];

    // SPP UUID service - this should work for most devices
    private final UUID MY_UUID_SECURE = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerSocket bluetoothServerSocket;

    // Detectar desconexión Bluetooth para detener momentaneamente el servicio
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive (Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        == BluetoothAdapter.STATE_OFF) {
                    MainActivity.debugTextview.append("\nBT SERVICE: BLUETOOTH OFF");
                    Log.d("BT SERVICE", "BLUETOOTH OFF");
                    stopMyService();
                    stopThread = true;
                }
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        == BluetoothAdapter.STATE_ON) {
                    Log.d("BT SERVICE", "BLUETOOTH ON");
                    MainActivity.debugTextview.append("\nBT SERVICE: BLUETOOTH ON");
                    stopThread = false;
                    checkBTState();
                    //bluetoothServiceThread.interrupt(); // le levantamos!
                }
            }
        }
    };

    // Mutex
    private static Object obj = new Object();

    public void write(String out) {
        // Create temporary object
        ConnectedThread r = mConnectedThread;
        // Perform the write unsynchronized
        r.write(out);
    }

    @SuppressWarnings("all")
    @Override
    public void onCreate() {
        super.onCreate();
        readCredentials();
        Log.d("BluetoothService", "BluetoothService onCreate()");
        registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        bluetoothIn = new Handler() {
            public void handleMessage(android.os.Message msg) {
                Log.d("DEBUG", "handleMessage");
                if (msg.what == handlerState) {                                     //if message is what we want
                    String readMessage = (String) msg.obj;                                                                // msg.arg1 = bytes from connect thread
                    recDataString.append(readMessage); //`enter code here`
                    Log.d("RECORDED", recDataString.toString());
                    String[] data = recDataString.toString().split(";");
                    if (data[0].equals("ping")) {
                        MainActivity.debugTextview.append("\nMessage: ping");
                        write("pong");
                    } else if (data[2].equals("lock")) {
                        write("lock");
                        MainActivity.debugTextview.append("\nMessage: lock");
                    } else {
                        int pid = Integer.parseInt(data[0].split(":")[1]);
                        int uid = Integer.parseInt(data[1].split(":")[1]);
                        data = data[2].split(":");
                        if (data[0].equals("cred")) { // credentials && uid == 0
                            if (data[1].equals("alberto")) {
                                write(findCredential("cred", "alberto", ""));
                            } else if (data[1].equals("alba")) {
                                write(findCredential("cred", "alba", ""));
                            } else {
                                write("no_pass");
                            }
                        } else if (data[0].equals("url")) {
                            if (data[1].equals("mail.uc3m.es") && uid == 1000) {
                                write(findCredential("url", "alberto", "mail.uc3m.es"));
                            } else if (data[1].equals("mail.google.es") && uid == 1001) {
                                write(findCredential("url", "alba", "mail.google.es"));
                            } else {
                                write("forbidden:forbidden");
                            }
                        } else {
                            write("forbidden:forbidden");
                        }
                    }
                    recDataString.delete(0, recDataString.length());                    //clear all string data
                }
            }
        };

        stopThread = false;
        startServiceWithNotification();
    }

    private String findCredential(String type, String user, String url) {
        for(int i=0; i < 4; i++) {
            String[] cred = creds[i].split(":");
            if (cred.length == 4 && cred[0].equals(type) && cred[1].equals(user) && cred[3].equals(url)) {
                return cred[1] + ":" + cred[2];
            } else if (cred[0].equals(type) && cred[1].equals(user)) {
                return cred[2];
            }
        }
        if (url.equals("")) {
            return "no_pass";
        } else {
            return "forbidden:forbidden";
        }
    }

    private void readCredentials() {
        File sdcard = Environment.getExternalStorageDirectory();
        Log.d("readcreds", sdcard.getAbsolutePath());
        File file = new File(sdcard,"/credentials.txt");
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                creds[count] = line;
                count++;
            }
            br.close();
        }
        catch (IOException e) {
            Log.d("creds", "no creds file");
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle extras = intent.getExtras();
        messageHandler = (Messenger) extras.get("MESSENGER");

        if (intent != null && intent.getAction().equals(ACTION_START_BLUETOOTH)) {
            startServiceWithNotification();
        }
        else {
            Log.d("DEBUG", "No start service");
            //bluetoothServiceThread.stopMyService();
        }
        return START_STICKY;
    }

    void stopMyService() {
        //stopForeground(true);
        //stopSelf();
        //closeSocket();
        stopThread = true;
        /*if (mConnectingThread != null) {
            mConnectingThread.closeSocket();
            mConnectingThread.stop();
        }*/
        Log.d("BluetoothServiceThread", "stop service. Probably no bluetooth?");
        MainActivity.debugTextview.append("\nBluetoothServiceThread: stop service");
        isServiceRunning = false;
    }

    @Override
    public void onDestroy() {
        stopMyService();
        isServiceRunning = false;
        stopForeground(true);
        stopSelf();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /*public void sendMessage(BluetoothServiceState state) {
        Message message = Message.obtain();
        switch (state) {
            case REQUEST_ENABLE_BLUETOOTH:
                message.arg1 = MainActivity.REQUEST_ENABLE_BLUETOOTH;
                break;
        }
        try {
            messageHandler.send(message);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }*/

    void startServiceWithNotification() {
        if (isServiceRunning) return;
        isServiceRunning = true;

        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        notificationIntent.setAction(ACTION_CHECK_BLUETOOTH);  // A string containing the action name C.ACTION_MAIN
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this)
                .setContentTitle(getResources().getString(R.string.bluetooth_service))
                .setShowWhen(false)
                .setContentText(getResources().getString(R.string.bluetooth_service_description))
                .setSmallIcon(R.drawable.ic_mobitoken_notification)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .build();
        notification.flags = notification.flags | Notification.FLAG_NO_CLEAR;     // NO_CLEAR makes the notification stay when the user performs a "delete all" command
        startForeground(NOTIFICATION_ID, notification);

        Log.d("BluetoothService", "BluetoothService started. Lets check Bluetooth state...");
        MainActivity.debugTextview.append("\nBluetoothService: started");
        checkBTState();
    }

    private void checkBTState() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.d("BluetoothServiceThread", "BLUETOOTH NOT SUPPORTED BY DEVICE, STOPPING SERVICE");
            stopSelf();
        } else {
            if (bluetoothAdapter.isEnabled()) {
                Log.d("BluetoothServiceThread", "Bluetooth enabled! BT address : " + bluetoothAdapter.getAddress() + " , bt name : " + bluetoothAdapter.getName());
                try {
                    Log.d("BluetoothServiceThread", "Attempting to create server");
                    bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("mobitoken", MY_UUID_SECURE);

                    mConnectingThread = new ConnectingThread(bluetoothServerSocket);
                    mConnectingThread.start();
                } catch (IOException e) {
                    Log.d("BluetoothServiceThread", "Something goes wrong");
                    e.printStackTrace();
                    stopSelf();
                }
            } else {
                Log.d("BluetoothServiceThread", "Bluetooth not enlabed");
                //sendMessage(BluetoothServiceState.REQUEST_ENABLE_BLUETOOTH);

                /*while(!bluetoothStatus) {
                    try {
                        Thread.sleep(Integer.MAX_VALUE);
                    } catch (InterruptedException e) {
                        // Nos levantamos!!!
                        break;
                    }
                }*/
                //stopSelf();
            }
        }
    }

    // New Class for Connecting Thread
    private class ConnectingThread extends Thread {
        private final BluetoothServerSocket bluetoothServerSocket;

        public ConnectingThread(BluetoothServerSocket serverSocket) {
            Log.d("ConnectingThread", "IN CONNECTING THREAD RUN");
            // Establish the Bluetooth socket connection.
            // Cancelling discovery as it may slow down connection
            // bluetoothAdapter.cancelDiscovery();
            bluetoothServerSocket = serverSocket;
        }

        @Override
        public void run() {

                BluetoothSocket socket = null;
                // Keep listening until exception occurs or a socket is returned
                while (true) {
                    // try {
                        try {
                            Log.d("ConnectingThread", "Waiting connection...");
                            socket = bluetoothServerSocket.accept();
                            Log.d("ConnectingThread", "BT socket connected");
                        } catch (IOException e) {
                            Log.d("ConnectingThread", "BT off?");
                            //stopSelf();
                            closeSocket();
                            //Thread.sleep(Long.MAX_VALUE);
                            break;
                            //continue;
                        }
                        // If a connection was accepted
                        if (socket != null) {
                            // Do work to manage the connection (in a separate thread)
                            mConnectedThread = new ConnectedThread(socket);
                            mConnectedThread.start();
                            Log.d("ConnectingThread", "Connected thread started");
                            //I send a character when resuming.beginning transmission to check device is connected
                            //If it is not an exception will be thrown in the write method and finish() will be called
                            //mConnectedThread.write("x");

                            //mConnectedThread.closeStreams();
                        }
                   /* } catch (InterruptedException e) {
                        Log.d("ConnectingThread", "me dormi y me despertaron");
                    } */
                }
        }

        public void closeSocket() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                bluetoothServerSocket.close();
            } catch (IOException e2) {
                //insert code to deal with this
                Log.d("ConnectingThread", e2.toString());
                Log.d("ConnectingThread", "socket closing failed, stopping service");
                //stopSelf();
            }
        }
    }

    // New Class for Connected Thread
    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        //creation of the connect thread
        ConnectedThread(BluetoothSocket socket) {
            Log.d("ConnectedThread", "Inside connected thread");
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                //Create I/O streams for connection
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.d("ConnectedThread", e.toString());
                Log.d("ConnectedThread", "UNABLE TO READ/WRITE, STOPPING SERVICE");
                //stopSelf();
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        @Override
        public void run() {
            Log.d("ConnectedThread", "Inside connected thread RUN");
            byte[] buffer = new byte[256];
            int bytes;

            // Keep looping to listen for received messages
            while (true && !stopThread) {
                try {
                    bytes = mmInStream.read(buffer);            //read bytes from input buffer
                    String readMessage = new String(buffer, 0, bytes);
                    Log.d("ConnectedThread", "Message:  " + readMessage);
                    // Send the obtained bytes to the UI Activity via handler
                    bluetoothIn.obtainMessage(handlerState, bytes, -1, readMessage).sendToTarget();
                } catch (IOException e) {
                    Log.d("ConnectedThread", e.toString());
                    Log.d("ConnectedThread", "UNABLE TO READ/WRITE");
                    //stopSelf();
                    break;
                }
            }
            closeStreams();
        }

        //write method
        public void write(String input) {
            byte[] msgBuffer = input.getBytes();           //converts entered String into bytes
            try {
                mmOutStream.write(msgBuffer);                //write bytes over BT connection via outstream
            } catch (IOException e) {
                //if you cannot write, close the application
                Log.d("ConnectedThread", "UNABLE TO READ/WRITE " + e.toString());
                Log.d("ConnectedThread", "UNABLE TO READ/WRITE, STOPPING SERVICE");
                //stopSelf();
            }
        }

        public void closeStreams() {
            try {
                //Don't leave Bluetooth sockets open when leaving activity
                mmInStream.close();
                mmOutStream.close();
            } catch (IOException e2) {
                //insert code to deal with this
                Log.d("ConnectedThread", e2.toString());
                Log.d("ConnectedThread", "STREAM CLOSING FAILED, STOPPING SERVICE");
                //stopSelf();
            }
        }
    }
}
