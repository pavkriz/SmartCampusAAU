// global variable "map"
function uhkInitialize() {
    var layer2 = new google.maps.KmlLayer({
        url: 'http://beacon.uhk.cz/webview/overlay/krizovi.kml',
        suppressInfoWindows: true,
        clickable: false,
        preserveViewport: true,
        map: map
    });

    var layerz = new google.maps.KmlLayer({
        url: 'http://beacon.uhk.cz/webview/overlay/J1NP.kml',
        suppressInfoWindows: true,
        clickable: false,
        preserveViewport: true,
        map: map
    });
}