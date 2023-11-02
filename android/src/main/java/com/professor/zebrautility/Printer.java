package com.professor.zebrautility;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.comm.TcpConnection;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.NetworkDiscoverer;

import java.util.ArrayList;
import java.util.HashMap;

import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class Printer implements MethodChannel.MethodCallHandler {

    private static final int ACCESS_COARSE_LOCATION_REQUEST_CODE = 100021;
    private static final int ON_DISCOVERY_ERROR_GENERAL = -1;
    private static final int ON_DISCOVERY_ERROR_BLUETOOTH = -2;
    private static final int ON_DISCOVERY_ERROR_LOCATION = -3;
    private Connection printerConnection;
    private ZebraPrinter printer;
    private Context context;
    private ActivityPluginBinding binding;
    private MethodChannel methodChannel;
    private String selectedAddress = null;
    private String macAddress = null;

    private static ArrayList<DiscoveredPrinter> discoveredPrinters = new ArrayList<>();
    private static ArrayList<DiscoveredPrinter> sendedDiscoveredPrinters = new ArrayList<>();
    private static int countDiscovery = 0;
    private static int countEndScan = 0;
    
    private boolean isSetupReady = false;

    public Printer(ActivityPluginBinding binding, BinaryMessenger binaryMessenger) {
        this.context = binding.getActivity();
        this.binding = binding;
        this.methodChannel = new MethodChannel(binaryMessenger, "ZebraPrinterObject" + this.toString());
        methodChannel.setMethodCallHandler(this);
    }

    public static void discoveryPrinters(final Context context, final MethodChannel methodChannel) {

        try {
            sendedDiscoveredPrinters.clear();
            for (DiscoveredPrinter dp : discoveredPrinters) {
                addNewDiscoverPrinter(dp, context, methodChannel);
            }
            countEndScan = 0;
            BluetoothDiscoverer.findPrinters(context, new DiscoveryHandler() {
                @Override
                public void foundPrinter(final DiscoveredPrinter discoveredPrinter) {
                    discoveredPrinters.add(discoveredPrinter);
                    ((Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            addNewDiscoverPrinter(discoveredPrinter, context, methodChannel);
                        }
                    });
                }

                @Override
                public void discoveryFinished() {
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }

                @Override
                public void discoveryError(String s) {
                    if (s.contains("Bluetooth radio is currently disabled"))
                        onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_BLUETOOTH, s);
                    else
                        onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_GENERAL, s);
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }
            });

            NetworkDiscoverer.findPrinters(new DiscoveryHandler() {
                @Override
                public void foundPrinter(DiscoveredPrinter discoveredPrinter) {
                    addNewDiscoverPrinter(discoveredPrinter, context, methodChannel);

                }

                @Override
                public void discoveryFinished() {
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }

                @Override
                public void discoveryError(String s) {
                    onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_GENERAL, s);
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void onDiscoveryError(Context context, final MethodChannel methodChannel, final int errorCode,
            final String errorText) {
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                HashMap<String, Object> arguments = new HashMap<>();
                arguments.put("ErrorCode", errorCode);
                arguments.put("ErrorText", errorText);
                methodChannel.invokeMethod("onDiscoveryError", arguments);
            }
        });

    }

    private static void addPrinterToDiscoveryPrinterList(DiscoveredPrinter discoveredPrinter) {
        for (DiscoveredPrinter dp : discoveredPrinters) {
            if (dp.address.equals(discoveredPrinter.address))
                return;
        }

        discoveredPrinters.add(discoveredPrinter);
    }

    private static void addNewDiscoverPrinter(final DiscoveredPrinter discoveredPrinter, Context context,
            final MethodChannel methodChannel) {

        addPrinterToDiscoveryPrinterList(discoveredPrinter);
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                for (DiscoveredPrinter dp : sendedDiscoveredPrinters) {
                    if (dp.address.equals(discoveredPrinter.address))
                        return;
                }
                sendedDiscoveredPrinters.add(discoveredPrinter);
                HashMap<String, Object> arguments = new HashMap<>();

                arguments.put("Address", discoveredPrinter.address);
                if (discoveredPrinter.getDiscoveryDataMap().get("SYSTEM_NAME") != null) {
                    arguments.put("Name", discoveredPrinter.getDiscoveryDataMap().get("SYSTEM_NAME"));
                    arguments.put("IsWifi", true);
                    methodChannel.invokeMethod("printerFound", arguments);
                } else {
                    arguments.put("Name", discoveredPrinter.getDiscoveryDataMap().get("FRIENDLY_NAME"));
                    arguments.put("IsWifi", false);
                    methodChannel.invokeMethod("printerFound", arguments);
                }
            }
        });
    }

    private static void finishScanPrinter(final Context context, final MethodChannel methodChannel) {
        if (countEndScan == 2) {
            if (discoveredPrinters.size() == 0) {
                if (discoveryPrintersAgain(context, methodChannel))
                    return;
            }
            ((Activity) context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    methodChannel.invokeMethod("onPrinterDiscoveryDone",
                            context.getResources().getString(R.string.done));
                }
            });
        }
    }

    private static boolean discoveryPrintersAgain(Context context, MethodChannel methodChannel) {
        System.out.print("Discovery printers again");
        countDiscovery++;
        if (countDiscovery < 2) {
            discoveryPrinters(context, methodChannel);
            return true;
        }
        return false;
    }

    private void checkPermission(final Context context, final MethodChannel.Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                binding.addRequestPermissionsResultListener(new PluginRegistry.RequestPermissionsResultListener() {
                    @Override
                    public boolean onRequestPermissionsResult(int requestCode, String[] permissions,
                            int[] grantResults) {
                        if (requestCode == ACCESS_COARSE_LOCATION_REQUEST_CODE) {
                            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                                result.success(true);
                            } else {
                                result.success(false);
                            }
                            return false;
                        }
                        return false;
                    }
                });

                ((Activity) context).requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                        ACCESS_COARSE_LOCATION_REQUEST_CODE);
            } else {
                result.success(true);
            }
        } else {
            result.success(true);
        }
    }

    public void print(final String data, final String ipAddress, final boolean isSetup) {
        new Thread(new Runnable() {
            public void run() {
                printerConnection = null;
                macAddress = ipAddress;
                setStatus(context.getString(R.string.connecting), context.getString(R.string.connectingColor));
                printerConnection = new BluetoothConnection(getMacAddress());
                // Check if we are already connected before attempting to work
                if (!printerConnection.isConnected()) {
                    // If unable to connect, handle it by preventing errors
                    // or try connecting again
                    try {
                        printerConnection.open();
                    } catch (ConnectionException e) {
                        // Handle the connection error here
                        e.printStackTrace();
                        return;
                    }
                }
                setStatus(context.getString(R.string.connected), context.getString(R.string.connectedColor));
                // When the connection is successful
                try {
                    printer = ZebraPrinterFactory.getInstance(printerConnection);

                    if (isSetup && !isSetupReady) {
                        isSetupReady = true;
                        String setting = "! U1 setvar \"print.tone\" \"60\"\n" +
                                "! U1 setvar \"media.type\" \"journal\"\n" +
                                "! U1 setvar \"bluetooth.le.controller_mode\" \"classic\"" +
                                "! U1 setvar \"bluetooth.minimum_security_mode\" \"1\"" +
                                "! U1 setvar \"media.speed\" \"10\"" +
                                "! U1 setvar \"power.low_battery_timeout\" \"0\"" +
                                "! U1 setvar \"power.sleep.enable\" \"off\"" +
                                "! U1 setvar \"bluetooth.enable\" \"on\"" +
                                "! U1 setvar \"bluetooth.discoverable\" \"on\"" +
                                "! U1 setvar \"power.dtr_power_off\" \"off\"" +
                                "! U1 setvar \"power.inactivity_timeout\" \"0\"" +
                                "! U1 setvar \"power.sleep.unassociated\" \"off\"" +
                                "! U1 setvar \"cradle.comm.handshake\" \"none\"" +
                                "! U1 setvar \"cradle.comm.baud\" \"9600\"" +
                                "! U1 setvar \"comm.handshake\" \"none\"" +
                                "! U1 setvar \"comm.baud\" \"9600\"";

                        byte[] bytesSetting = convertDataToByte(setting);
                        printerConnection.write(bytesSetting);
                    }

                    byte[] bytes = convertDataToByte(data);
                    setStatus(context.getString(R.string.sending_data), context.getString(R.string.connectingColor));
                    printerConnection.write(bytes);
                    DemoSleeper.sleep(1600);
                    setStatus(context.getResources().getString(R.string.done),
                            context.getString(R.string.connectedColor));
                } catch (ZebraPrinterLanguageUnknownException e) {
                    // Handle the ZebraPrinterLanguageUnknownException error here
                    e.printStackTrace();
                    // Do what you need to do when an error occurs
                } catch (ConnectionException e) {
                    // Handle the ConnectionException error here
                    e.printStackTrace();
                    // Do what you need to do when an error occurs
                } finally {
                    // Close the connection, regardless of errors
                    try {
                        setStatus(context.getResources().getString(R.string.disconnecting),
                                context.getString(R.string.connectingColor));
                        printerConnection.close();
                        setStatus(context.getResources().getString(R.string.disconnect),
                                context.getString(R.string.disconnectColor));
                    } catch (ConnectionException e) {
                        // Handle the connection close error here
                        e.printStackTrace();
                        // Do what you need to do when an error occurs
                    }
                }
            }
        }).start();
    }

    private String getMacAddress() {
        return macAddress;
    }

    private void setStatus(final String message, final String color) {
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Printer set status: " + message);
                HashMap<String, Object> arguments = new HashMap<>();
                arguments.put("Status", message);
                arguments.put("Color", color);
                methodChannel.invokeMethod("changePrinterStatus", arguments);
            }
        });
    }

    private int getTcpPortNumber() {
        return 6101;
    }

    private int getGenericPortNumber() {
        return 9100;
    }

    private byte[] convertDataToByte(String data) {
        return data.getBytes();
    }

    public static String getZplCode(Bitmap bitmap, Boolean addHeaderFooter, int rotation) {
        ZPLConverter zp = new ZPLConverter();
        zp.setCompressHex(true);
        zp.setBlacknessLimitPercentage(50);
        Bitmap grayBitmap = toGrayScale(bitmap, rotation);
        return zp.convertFromImage(grayBitmap, addHeaderFooter);
    }

    public static Bitmap toGrayScale(Bitmap bmpOriginal, int rotation) {
        int width, height;
        bmpOriginal = rotateBitmap(bmpOriginal, rotation);
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();
        Bitmap grayScale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        grayScale.eraseColor(Color.WHITE);
        Canvas c = new Canvas(grayScale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);
        return grayScale;
    }

    public static Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    public void setSettings(String settings, final String ipAddress) {
        print(settings, ipAddress, false);
    }

    public void setDarkness(int darkness, final String ipAddress) {
        String setting = "! U1 setvar \"print.tone\" \"" + darkness + "\"\n";
        setSettings(setting, ipAddress);
    }

    public void setMediaType(String mediaType, final String ipAddress) {
        String settings;
        if (mediaType.equals("Label")) {
            settings = "! U1 setvar \"media.type\" \"label\"\n" +
                    "! U1 setvar \"media.sense_mode\" \"gap\"\n" +
                    "~jc^xa^jus^xz";
        } else if (mediaType.equals("BlackMark")) {
            settings = "! U1 setvar \"media.type\" \"label\"\n" +
                    "! U1 setvar \"media.sense_mode\" \"bar\"\n" +
                    "~jc^xa^jus^xz";
        } else {
            settings = "! U1 setvar \"print.tone\" \"0\"\n" +
                    "! U1 setvar \"media.type\" \"journal\"\n";
        }
        setSettings(settings, ipAddress);
    }

    private void convertBase64ImageToZPLString(String data, int rotation, MethodChannel.Result result) {
        try {
            byte[] decodedString = Base64.decode(data, Base64.DEFAULT);
            Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
            result.success(Printer.getZplCode(decodedByte, false, rotation));
        } catch (Exception e) {
            result.error("-1", "Error", null);
        }
    }

    @Override
    public void onMethodCall(@NonNull final MethodCall call, @NonNull final MethodChannel.Result result) {
        if (call.method.equals("print")) {
            String data = call.argument("Data").toString();
            String ipAddress = call.argument("IpAddress").toString();
            boolean isSetup = call.argument("IsSetup");
            print(data, ipAddress, isSetup);
        } else if (call.method.equals("checkPermission")) {
            checkPermission(context, result);
        } else if (call.method.equals("convertBase64ImageToZPLString")) {
            convertBase64ImageToZPLString(call.argument("Data").toString(),
                    Integer.valueOf(call.argument("rotation").toString()), result);
        } else if (call.method.equals("discoverPrinters")) {
            if (checkIsLocationNetworkProviderIsOn()) {
                discoveryPrinters(context, methodChannel);
            } else {
                onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_LOCATION, "Your location service is off.");
            }
        } else if (call.method.equals("setMediaType")) {
            String mediaType = call.argument("MediaType").toString();
            String ipAddress = call.argument("IpAddress").toString();
            setMediaType(mediaType, ipAddress);
        } else if (call.method.equals("setSettings")) {
            String settingCommand = call.argument("SettingCommand").toString();
            String ipAddress = call.argument("IpAddress").toString();
            setSettings(settingCommand, ipAddress);
        } else if (call.method.equals("setDarkness")) {
            int darkness = call.argument("Darkness");
            String ipAddress = call.argument("IpAddress").toString();
            setDarkness(darkness, ipAddress);
        } else {
            result.notImplemented();
        }
    }

    private boolean checkIsLocationNetworkProviderIsOn() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            return false;
        }
    }
}
