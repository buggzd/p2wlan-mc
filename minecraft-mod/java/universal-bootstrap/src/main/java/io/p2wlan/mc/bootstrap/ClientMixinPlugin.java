package io.p2wlan.mc.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import static io.p2wlan.mc.bootstrap.BootstrapFailure.Code;

/** Side comes from Mixin itself; no client or loader class is initialized. */
public final class ClientMixinPlugin implements IMixinConfigPlugin {
    private final UniversalEnvironment.Selection selection;
    private final Supplier<UniversalEnvironment.Side> side;
    private UniversalEnvironment.Family family;
    public ClientMixinPlugin() {
        this(UniversalEnvironment.current(), () -> switch (MixinEnvironment.getDefaultEnvironment().getSide()) {
            case CLIENT -> UniversalEnvironment.Side.CLIENT;
            case SERVER -> UniversalEnvironment.Side.SERVER;
            case UNKNOWN -> UniversalEnvironment.Side.UNKNOWN;
        });
    }
    public ClientMixinPlugin(UniversalEnvironment.Selection selection, Supplier<UniversalEnvironment.Side> side) {
        this.selection = Objects.requireNonNull(selection); this.side = Objects.requireNonNull(side);
    }
    @Override public void onLoad(String packageName) {
        var next = UniversalEnvironment.family(packageName, true);
        if (family != null && family != next) throw new BootstrapFailure(Code.DISPATCH_CHANGED);
        family = next;
    }
    private UniversalEnvironment.Family binding() {
        if (family == null) throw new BootstrapFailure(Code.DISPATCH_UNBOUND);
        return family;
    }
    @Override public String getRefMapperConfig() {
        return selection.family() == binding() && selection.loader() == UniversalEnvironment.Loader.FABRIC
                ? UniversalEnvironment.CLIENT_REFMAP : null;
    }
    @Override public List<String> getMixins() {
        var bound = binding();
        // Inactive packages never consult side or enter Mixin's target list.
        return selection.family() == bound ? UniversalEnvironment.clientMixins(selection, bound, side.get()) : List.of();
    }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
