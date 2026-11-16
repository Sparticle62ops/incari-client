import io.netty.bootstrap.Bootstrap;
import io.netty.channel.local.LocalChannel;

/**
 * {@code Bootstrap.channel(Class)} in the browser build: it works for a channel class whose
 * constructor tools/webbuild's ReflectionDeclarations declares, and throws for one it doesn't, as
 * it did for PlatformSocketChannel in the server list's legacy ping (which crashed the game).
 */
public final class ChannelClassProbe {

    private ChannelClassProbe() {}

    /** Public, with a public no-arg constructor, like PlatformSocketChannel; not declared. */
    public static final class Undeclared extends LocalChannel {
        public Undeclared() {}
    }

    public static void main(String[] args) {
        System.setProperty("io.netty.noUnsafe", "true");
        try {
            new Bootstrap().channel(LocalChannel.class);
            System.out.println("channel(Class) accepted a declared class");
        } catch (Throwable t) {
            System.out.println("probe failed: channel(Class) threw for a declared class: " + t);
            return;
        }
        try {
            new Bootstrap().channel(Undeclared.class);
            System.out.println("probe failed: channel(Class) accepted an undeclared class");
        } catch (Throwable t) {
            System.out.println("channel(Class) threw " + t);
            System.out.println("probe passed");
        }
    }
}
