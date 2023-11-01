import 'package:flutter/services.dart';

enum EnumMediaType { Label, BlackMark, Journal }

enum Command { calibrate, mediaType, darkness }

class ZebraPrinter {
  late MethodChannel channel;

  Function? onPrinterFound;
  Function? onPrinterDiscoveryDone;
  Function? onDiscoveryError;
  Function? onChangePrinterStatus;
  Function? onPermissionDenied;

  bool isRotated = false;

  ZebraPrinter(
    String id,
    this.onPrinterFound,
    this.onPrinterDiscoveryDone,
    this.onDiscoveryError,
    this.onChangePrinterStatus, {
    this.onPermissionDenied,
  }) {
    channel = MethodChannel('ZebraPrinterObject' + id);
    channel.setMethodCallHandler(nativeMethodCallHandler);
  }

  discoveryPrinters() {
    channel.invokeMethod("checkPermission").then((isGrantPermission) {
      if (isGrantPermission)
        channel.invokeMethod("discoverPrinters");
      else {
        if (onPermissionDenied != null) onPermissionDenied!();
      }
    });
  }

  _setSettings(Command setting, dynamic values, String ipAddress) {
    String command = "";
    switch (setting) {
      case Command.mediaType:
        if (values == EnumMediaType.BlackMark) {
          command = '''
          ! U1 setvar "media.type" "label"
          ! U1 setvar "media.sense_mode" "bar"
          ''';
        } else if (values == EnumMediaType.Journal) {
          command = '''
          ! U1 setvar "media.type" "journal"
          ''';
        } else if (values == EnumMediaType.Label) {
          command = '''
          ! U1 setvar "media.type" "label"
           ! U1 setvar "media.sense_mode" "gap"
          ''';
        }

        break;
      case Command.calibrate:
        command = '''~jc^xa^jus^xz''';
        break;
      case Command.darkness:
        command = '''! U1 setvar "print.tone" "$values"''';
        break;
    }

    if (setting == Command.calibrate) {
      command = '''~jc^xa^jus^xz''';
    }

    try {
      channel.invokeMethod("setSettings", {"SettingCommand": command, "IpAddress": ipAddress});
    } on PlatformException catch (e) {}
  }

  setDarkness(int darkness, String ipAddress) {
    _setSettings(Command.darkness, darkness.toString(), ipAddress);
  }

  setMediaType(EnumMediaType mediaType, String ipAddress) {
    _setSettings(Command.mediaType, mediaType, ipAddress);
  }

  printSetting(String command, String ipAddress) {
    channel.invokeMethod("setSettings", {"SettingCommand": command, "IpAddress": ipAddress});
  }

  print(String data, String ipAddress, bool isSetup) {
    if (!data.contains("^PON")) data = data.replaceAll("^XA", "^XA^PON");

    if (isRotated) {
      data = data.replaceAll("^PON", "^POI");
    }
    channel.invokeMethod("print", {"Data": data, "IpAddress": ipAddress , "IsSetup": isSetup});
  }

  calibratePrinter(String ipAddress) {
    _setSettings(Command.calibrate, null, ipAddress);
  }

  Future<dynamic> nativeMethodCallHandler(MethodCall methodCall) async {
    if (methodCall.method == "printerFound") {
      onPrinterFound!(
        methodCall.arguments["Name"],
        methodCall.arguments["Address"],
        methodCall.arguments["IsWifi"].toString() == "true" ? true : false,
      );
    } else if (methodCall.method == "changePrinterStatus") {
      onChangePrinterStatus!(
        methodCall.arguments["Status"],
        methodCall.arguments["Color"],
      );
    } else if (methodCall.method == "onPrinterDiscoveryDone") {
      onPrinterDiscoveryDone!();
    } else if (methodCall.method == "onDiscoveryError") {
      onDiscoveryError!(
        methodCall.arguments["ErrorCode"],
        methodCall.arguments["ErrorText"],
      );
    }
    return null;
  }

  String? id;
}
