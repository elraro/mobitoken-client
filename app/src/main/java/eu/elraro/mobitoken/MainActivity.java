package eu.elraro.mobitoken;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import static eu.elraro.mobitoken.MainActivity.REQUEST_ENABLE_BLUETOOTH;

public class MainActivity extends AppCompatActivity {

    public static TextView debugTextview;
    Button unlockButton;

    boolean bluetoothStatus = false;

    private static final int REQUEST_ENABLE_BT = 1;

    final private int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 124;

    public static Handler messageHandler = new MessageHandler();

    public static final int REQUEST_ENABLE_BLUETOOTH = 1;

    public static MainActivity app;


    @TargetApi(Build.VERSION_CODES.M)
    private void fuckMarshMallow() {
        List<String> permissionsNeeded = new ArrayList<String>();

        final List<String> permissionsList = new ArrayList<String>();
        if (!addPermission(permissionsList, Manifest.permission.ACCESS_FINE_LOCATION))
            permissionsNeeded.add("Show Location");
        if (!addPermission(permissionsList, Manifest.permission.READ_EXTERNAL_STORAGE))
            permissionsNeeded.add("Read sd card");
        if (!addPermission(permissionsList, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            permissionsNeeded.add("Write sd card");

        if (permissionsList.size() > 0) {
            if (permissionsNeeded.size() > 0) {

                // Need Rationale
                String message = "App need access to " + permissionsNeeded.get(0);

                for (int i = 1; i < permissionsNeeded.size(); i++)
                    message = message + ", " + permissionsNeeded.get(i);

                showMessageOKCancel(message,
                        new DialogInterface.OnClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
                                        REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
                            }
                        });
                return;
            }
            requestPermissions(permissionsList.toArray(new String[permissionsList.size()]),
                    REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
            return;
        }

        Toast.makeText(MainActivity.this, "No new Permission Required!!", Toast.LENGTH_SHORT)
                .show();
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener okListener) {
        new android.app.AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton("OK", okListener)
                .setNegativeButton("Cancel", null)
                .create()
                .show();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean addPermission(List<String> permissionsList, String permission) {

        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission);
            // Check for Rationale Option
            if (!shouldShowRequestPermissionRationale(permission))
                return false;
        }
        return true;
    }

    // Called when the activity is first created
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity.app = this;

        setContentView(R.layout.activity_main);

        debugTextview = (TextView) findViewById(R.id.debugTextview);
        debugTextview.append("\n...In onCreate()...");

        if (Build.VERSION.SDK_INT >= 23) { //23
            // Marshmallow+ Permission APIs
            fuckMarshMallow();
        }

        Intent bluetoothServiceIntent = new Intent(getApplicationContext(), BluetoothService.class);
        bluetoothServiceIntent.putExtra("MESSENGER", new Messenger(messageHandler));
        bluetoothServiceIntent.setAction(BluetoothService.ACTION_START_BLUETOOTH);
        startService(bluetoothServiceIntent);


        unlockButton = (Button) findViewById(R.id.unlockButton);
        unlockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Create a data stream so we can talk to server.
                debugTextview.append("\nClick in lock/unlock");
                debugTextview.append("\nSending message to server...");
            }
        });

    }

    public void onStart() {
        super.onStart();
        debugTextview.append("\n...In onStart()...");
    }

    public void onResume() {
        super.onResume();
        debugTextview.append("\n...In onResume...\n...Attempting client connect...");
    }

    public void onPause() {
        super.onPause();
        debugTextview.append("\n...In onPause()...");
    }

    public void onStop() {
        super.onStop();
        debugTextview.append("\n...In onStop()...");
    }

    public void onDestroy() {
        super.onDestroy();
        debugTextview.append("\n...In onDestroy()...");
    }

    public void AlertBox( String title, String message ){
        new AlertDialog.Builder(this)
                .setTitle( title )
                .setMessage( message + " Press OK to exit." )
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface arg0, int arg1) {
                        finish();
                    }
                }).show();
    }

    static class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message message) {
            int state = message.arg1;
            switch (state) {
                case REQUEST_ENABLE_BLUETOOTH:
                    MainActivity.app.AlertBox("Bluetooth off", "Enable Bluetooth!");
                    break;
            }
        }
    }
}


