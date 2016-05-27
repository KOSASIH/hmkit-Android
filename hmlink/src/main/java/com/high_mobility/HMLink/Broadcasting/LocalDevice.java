package com.high_mobility.HMLink.Broadcasting;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import com.high_mobility.btcore.HMBTCore;
import com.high_mobility.btcore.HMDevice;
import com.high_mobility.HMLink.Device;
import com.high_mobility.HMLink.LinkException;
import com.high_mobility.HMLink.AccessCertificate;
import com.high_mobility.HMLink.DeviceCertificate;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Created by ttiganik on 12/04/16.
 *
 * LocalDevice acts as a gateway to the application's capability to broadcast itself and handle Link connectivity.
 *
 */
public class LocalDevice extends Device {
    static final String TAG = "HMLink";

    public enum State { BLUETOOTH_UNAVAILABLE, IDLE, BROADCASTING }

    Context ctx;
    Storage storage;
    byte[] privateKey;
    byte[] CAPublicKey;
    LocalDeviceListener listener;

    BluetoothManager mBluetoothManager;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    BluetoothGattServer GATTServer;
    GATTServerCallback gattServerCallback;

    BluetoothGattCharacteristic readCharacteristic;
    BluetoothGattCharacteristic writeCharacteristic;
    Handler mainThreadHandler;

    BTCoreInterface coreInterface;
    HMBTCore core = new HMBTCore();

    State state = State.IDLE;
    Link[] links = new Link[0];
    static LocalDevice instance = null;

    /**
     * @return The shared LocalDevice object.
     */
    public static LocalDevice getInstance() {
        if (instance == null) {
            instance = new LocalDevice();
        }

        return instance;
    }

    /**
     * The possible states of the local device are represented by the enum LocalDevice.State.
     *
     * @return The current state of the LocalDevice.
     * @see LocalDevice.State
     */
    public State getState() {
        return state;
    }

    /**
     * In order to receive LocalDevice events, a listener must be set.
     *
     * @param listener The listener instance to receive LocalDevice events.
     */
    public void setListener(LocalDeviceListener listener) {
        this.listener = listener;
    }

    /**
     * Set the device certificate and private key before using any other functionality.
     *
     * @param certificate The device certificate.
     * @param privateKey 32 byte private key with elliptic curve Prime 256v1.
     * @param CAPublicKey 64 byte public key of the Certificate Authority.
     * @param ctx The application context.
     */
    public void setDeviceCertificate(DeviceCertificate certificate, byte[] privateKey, byte[] CAPublicKey, Context ctx) {
        this.ctx = ctx;
        storage = new Storage(ctx);

        this.certificate = certificate;
        this.privateKey = privateKey;
        this.CAPublicKey = CAPublicKey;
        storage = new Storage(ctx);
        createAdapter();
        mainThreadHandler = new Handler(ctx.getMainLooper());
        coreInterface = new BTCoreInterface(this);
        core.HMBTCoreInit(coreInterface);
    }

    /**
     * @return The certificates that are registered on the LocalDevice.
     */
    public AccessCertificate[] getRegisteredCertificates() {
        return storage.getRegisteredCertificates(certificate.getSerial());
    }

    /**
     * @return The certificates that are stored in the device's database for other devices.
     */
    public AccessCertificate[] getStoredCertificates() {
        return storage.getStoredCertificates(certificate.getSerial());
    }

    /**
     * @return The Links currently connected to the LocalDevice.
     */
    public Link[] getLinks() {
        return links;
    }

    /**
     * Start broadcasting the LocalDevice via BLE advertising.
     *
     * @throws LinkException	    An exception with either UNSUPPORTED or BLUETOOTH_OFF code.
     */
    public void startBroadcasting() throws LinkException {
        if (state == State.BROADCASTING) return; // already broadcasting

        checkIfBluetoothIsEnabled();

        createGATTServer();

        // start advertising
        if (mBluetoothLeAdvertiser == null) {
            mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        }

        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        final UUID advertiseUUID = ByteUtils.UUIDFromByteArray(ByteUtils.concatBytes(certificate.getIssuer(), certificate.getAppIdentifier()));

        final AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(advertiseUUID))
                .build();

        mBluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback);
    }

    /**
     * Stops the advertisements and disconnects all the links.
     */
    public void stopBroadcasting() {
        // stopAdvertising clears the GATT server as well.
        // This causes all connection to fail with the link because there is no GATT server.

        for (int i = getLinks().length - 1; i >= 0; i--) {
            GATTServer.cancelConnection(getLinks()[i].btDevice);
        }

        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
        }

        setState(State.IDLE);

    }

    /**
     * Registers the AccessCertificate for the device, enabling authenticated
     * connection to another device.
     *
     * @param certificate The certificate that can be used by the Device to authorised Links
     * @throws LinkException When this device's certificate hasn't been set, the given certificates
     *                       providing serial doesn't match with this device's serial or
     *                       the storage is full.
     */
    public void registerCertificate(AccessCertificate certificate) throws LinkException {
        if (this.certificate == null) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        if (Arrays.equals(this.certificate.getSerial(), certificate.getProviderSerial()) == false) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        storage.storeCertificate(certificate);
    }

    /**
     * Stores a Certificate to Device's storage. This certificate is usually read by other Devices.
     *
     * @param certificate The certificate that will be saved to the database
     * @throws LinkException When the storage is full or certificate has not been set
     */
    public void storeCertificate(AccessCertificate certificate) throws LinkException {
        storage.storeCertificate(certificate);
    }

    /**
     * Revokes a stored certificate from Device's storage. The stored certificate and its
     * accompanying registered certificate are deleted from the storage.
     *
     * @param serial The 9-byte serial number of the access providing device
     * @throws LinkException When there are no matching certificate pairs for this serial.
     */
    public void revokeCertificate(byte[] serial) throws LinkException {
        if (storage.certWithGainingSerial(serial) == null
                || storage.certWithProvidingSerial(serial) == null) {
            throw new LinkException(LinkException.LinkExceptionCode.INTERNAL_ERROR);
        }

        storage.deleteCertificateWithGainingSerial(serial);
        storage.deleteCertificateWithProvidingSerial(serial);
    }

    /**
     * Deletes the saved certificates, resets the Bluetooth connection and stops broadcasting.
     */
    public void reset() {
        storage.resetStorage();
        stopBroadcasting();

        if (GATTServer != null) {
            GATTServer.clearServices();
            GATTServer.close();
            GATTServer = null;
        }
    }

    @Override
    public String getName() {
        return mBluetoothAdapter.getName();
    }

    int didResolveDevice(HMDevice device) {
        for (int i = 0; i < links.length; i++) {
            Link link = links[i];
            if (Arrays.equals(link.getAddressBytes(), device.getMac())) {
                link.setHmDevice(device);
                return i;
            }
        }

        return -1;
    }

    byte[] onCommandReceived(HMDevice device, byte[] data) {
        BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(device.getMac());
        int linkIndex = linkIndexForBTDevice(btDevice);

        if (linkIndex > -1) {
            Link link = links[linkIndex];
            return link.onCommandReceived(data);
        }
        else {
            Log.e(TAG, "no link for custom command received");
            return null;
        }
    }

    void onCommandResponseReceived(HMDevice device, byte[] data) {
        BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(device.getMac());
        int linkIndex = linkIndexForBTDevice(btDevice);

        if (linkIndex > -1) {
            Link link = links[linkIndex];
            link.onCommandResponseReceived(data);
        }
    }

    void didReceiveLink(BluetoothDevice device) {
        // add a new link to the array

        final Link link = new Link(device, this);
        Link[] newLinks = new Link[links.length + 1];

        for (int i = 0; i < links.length; i++) {
            newLinks[i] = links[i];
        }

        newLinks[links.length] = link;
        links = newLinks;

        link.setState(Link.State.CONNECTED);

        if (listener != null) {
            final LocalDevice devicePointer = this;
            devicePointer.mainThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    devicePointer.listener.onLinkReceived(link);
                }
            });
        }
    }

    void didLoseLink(HMDevice device) {
        if (Device.loggingLevel.getValue() >= Device.LoggingLevel.All.getValue()) Log.i(TAG, "lose link " + ByteUtils.hexFromBytes(device.getMac()));

        BluetoothDevice btDevice = mBluetoothAdapter.getRemoteDevice(device.getMac());
        int linkIndex = linkIndexForBTDevice(btDevice);

        if (linkIndex > -1) {
            // remove the link from the array
            final Link link = links[linkIndex];

            if (link.state != Link.State.DISCONNECTED) {
                GATTServer.cancelConnection(link.btDevice);
            }

            Link[] newLinks = new Link[links.length - 1];

            for (int i = 0; i < links.length; i++) {
                if (i < linkIndex) {
                    newLinks[i] = links[i];
                }
                else if (i > linkIndex) {
                    newLinks[i - 1] = links[i];
                }
            }

            links = newLinks;

            // set new adapter name
            if (links.length == 0) {
                setAdapterName();
            }

            link.setState(Link.State.DISCONNECTED);

            // invoke the listener listener
            if (listener != null) {
                final LocalDevice devicePointer = this;
                devicePointer.mainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        devicePointer.listener.onLinkLost(link);
                    }
                });
            }
        }
        else {
            Log.e(TAG, "no link for lose device");
        }
    }

    int didReceivePairingRequest(HMDevice device) {
        int linkIndex = didResolveDevice(device);

        if (linkIndex > -1) {
            final Link link = links[linkIndex];
            return link.didReceivePairingRequest();
        }
        else {
            Log.e(TAG, "no link for pairingResponse");
            return 1;
        }
    }

    void setAdapterName() {
        byte[] serialBytes = new byte[4];
        new Random().nextBytes(serialBytes);
        mBluetoothAdapter.setName(ByteUtils.hexFromBytes(serialBytes));
    }

    void writeData(byte[] mac, byte[] value) {
        if (Device.loggingLevel.getValue() >= Device.LoggingLevel.Debug.getValue()) Log.i(TAG, "write " + ByteUtils.hexFromBytes(mac) + " " + ByteUtils.hexFromBytes(value));
        Link link = getLinkForMac(mac);
        if (link != null) {
            readCharacteristic.setValue(value);
            GATTServer.notifyCharacteristicChanged(link.btDevice, readCharacteristic, false);
        }
        else {
            Log.e(TAG, "link does not exist for write");
        }
    }

    protected boolean isReadCharacteristic(UUID characteristicUUID) {
        return READ_CHAR_UUID.equals(characteristicUUID);
    }

    private Link getLinkForMac(byte[] mac) {
        for (int i = 0; i < links.length; i++) {
            Link link = links[i];

            if (Arrays.equals(link.getAddressBytes(), mac)) {
                return link;
            }
        }

        return null;
    }

    private int linkIndexForBTDevice(BluetoothDevice device) {
        for (int i = 0; i < links.length; i++) {
            Link link = links[i];

            if (link.btDevice.getAddress().equals(device.getAddress())) {
                return i;
            }
        }

        return -1;
    }

    private void createGATTServer() {
        if (GATTServer == null) {
            gattServerCallback = new GATTServerCallback(LocalDevice.getInstance());
            GATTServer = mBluetoothManager.openGattServer(ctx, gattServerCallback);

            if (Device.loggingLevel.getValue() >= Device.LoggingLevel.All.getValue()) Log.i(TAG, "createGATTServer");
            // create the service
            BluetoothGattService service = new BluetoothGattService(SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);

            readCharacteristic =
                    new BluetoothGattCharacteristic(READ_CHAR_UUID,
                            BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                            BluetoothGattCharacteristic.PERMISSION_READ);

            UUID confUUUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
            readCharacteristic.addDescriptor(new BluetoothGattDescriptor(confUUUID, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

            writeCharacteristic =
                    new BluetoothGattCharacteristic(WRITE_CHAR_UUID,
                            BluetoothGattCharacteristic.PROPERTY_WRITE,
                            BluetoothGattCharacteristic.PERMISSION_WRITE);

            service.addCharacteristic(readCharacteristic);
            service.addCharacteristic(writeCharacteristic);

            GATTServer.addService(service);
        }
        else {
            if (Device.loggingLevel.getValue() >= Device.LoggingLevel.All.getValue()) Log.i(TAG, "createGATTServer: already exists");
        }
    }

    private void createAdapter() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            setAdapterName();
            if (Device.loggingLevel.getValue() >= Device.LoggingLevel.All.getValue()) Log.i(TAG, "Create adapter " + mBluetoothAdapter.getName());
        }
    }

    private void checkIfBluetoothIsEnabled() throws LinkException {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            setState(State.BLUETOOTH_UNAVAILABLE);
            throw new LinkException(LinkException.LinkExceptionCode.BLUETOOTH_OFF);
        }

        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            setState(State.BLUETOOTH_UNAVAILABLE);
            throw new LinkException(LinkException.LinkExceptionCode.UNSUPPORTED);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            if (Device.loggingLevel.getValue() >= Device.LoggingLevel.All.getValue()) Log.i(TAG, "Start advertise " + mBluetoothAdapter.getName());
            setState(State.BROADCASTING);
        }

        @Override
        public void onStartFailure(int errorCode) {
            if (errorCode != 3) {
                Log.w(TAG, "Start Advertise Failed: " + errorCode);
                setState(State.IDLE);
            }
            else if (state != State.BROADCASTING) {
                setState(State.BROADCASTING);
            }
        }
    };

    private void setState(final State state) {
        if (this.state != state) {
            final State oldState = this.state;
            this.state = state;

            if (listener != null) {
                mainThreadHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onStateChanged(state, oldState);
                    }
                });
            }
        }
    }
}
