package top.yexkr.findscanner.scanner;

interface IWifiScanService {
    String startScan() = 1;
    void destroy() = 16777114;
}
