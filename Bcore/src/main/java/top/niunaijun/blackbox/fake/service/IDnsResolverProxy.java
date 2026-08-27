package top.niunaijun.blackbox.fake.service;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/** Passes DNS operations through to Android's real resolver. */
public class IDnsResolverProxy extends BinderInvocationStub {
    public static final String TAG = "IDnsResolverProxy";
    public static final String DNS_RESOLVER_SERVICE = "dnsresolver";

    public IDnsResolverProxy() {
        super(BRServiceManager.get().getService(DNS_RESOLVER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return null;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        // Android's resolver is already usable from the host network; do not replace it.
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

    @ProxyMethod("resolveDns") public static class ResolveDns extends PassthroughHook {}
    @ProxyMethod("setPrivateDnsConfiguration") public static class SetPrivateDnsConfiguration extends PassthroughHook {}
    @ProxyMethod("setDnsServersForNetwork") public static class SetDnsServersForNetwork extends PassthroughHook {}
    @ProxyMethod("isNetworkValidated") public static class IsNetworkValidated extends PassthroughHook {}
    @ProxyMethod("setDnsQueryTimeout") public static class SetDnsQueryTimeout extends PassthroughHook {}
    @ProxyMethod("getDnsResolverStats") public static class GetDnsResolverStats extends PassthroughHook {}
}
