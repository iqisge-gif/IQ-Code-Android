package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;

/** Passes connectivity queries through to the host network service unchanged. */
@ScanClass(VpnCommonProxy.class)
public class IConnectivityManagerProxy extends BinderInvocationStub {
    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(
                BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    private abstract static class PassthroughHook extends MethodHook {
        @Override
        protected Object hook(Object who, java.lang.reflect.Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfo extends PassthroughHook {}
    @ProxyMethod("getAllNetworkInfo") public static class GetAllNetworkInfo extends PassthroughHook {}
    @ProxyMethod("getAllNetworks") public static class GetAllNetworks extends PassthroughHook {}
    @ProxyMethod("getNetworkCapabilities") public static class GetNetworkCapabilities extends PassthroughHook {}
    @ProxyMethod("getActiveNetwork") public static class GetActiveNetwork extends PassthroughHook {}
    @ProxyMethod("getActiveNetworkInfo") public static class GetActiveNetworkInfo extends PassthroughHook {}
    @ProxyMethod("getLinkProperties") public static class GetLinkProperties extends PassthroughHook {}
    @ProxyMethod("getPrivateDnsServerName") public static class GetPrivateDnsServerName extends PassthroughHook {}
    @ProxyMethod("isPrivateDnsActive") public static class IsPrivateDnsActive extends PassthroughHook {}
    @ProxyMethod("getDnsServers") public static class GetDnsServers extends PassthroughHook {}
    @ProxyMethod("isNetworkValidated") public static class IsNetworkValidated extends PassthroughHook {}
    @ProxyMethod("requestNetwork") public static class RequestNetwork extends PassthroughHook {}
    @ProxyMethod("registerNetworkCallback") public static class RegisterNetworkCallback extends PassthroughHook {}
    @ProxyMethod("registerDefaultNetworkCallback") public static class RegisterDefaultNetworkCallback extends PassthroughHook {}
    @ProxyMethod("getActiveNetworkInfoForUid") public static class GetActiveNetworkInfoForUid extends PassthroughHook {}
    @ProxyMethod("addDefaultNetworkActiveListener") public static class AddDefaultNetworkActiveListener extends PassthroughHook {}
    @ProxyMethod("removeDefaultNetworkActiveListener") public static class RemoveDefaultNetworkActiveListener extends PassthroughHook {}
    @ProxyMethod("isActiveNetworkMetered") public static class IsActiveNetworkMetered extends PassthroughHook {}
    @ProxyMethod("getNetworkForType") public static class GetNetworkForType extends PassthroughHook {}
    @ProxyMethod("registerNetworkCallback") public static class RegisterNetworkCallbackWithRequest extends PassthroughHook {}
    @ProxyMethod("unregisterNetworkCallback") public static class UnregisterNetworkCallback extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithNetwork extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithInt extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString2 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString3 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString4 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString5 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString6 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString7 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString8 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString9 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString10 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString11 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString12 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString13 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString14 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString15 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString16 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString17 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString18 extends PassthroughHook {}
    @ProxyMethod("getNetworkInfo") public static class GetNetworkInfoWithString19 extends PassthroughHook {}
}
