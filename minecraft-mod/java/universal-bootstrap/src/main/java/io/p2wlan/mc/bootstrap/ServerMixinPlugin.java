package io.p2wlan.mc.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import static io.p2wlan.mc.bootstrap.BootstrapFailure.Code;

/** Only the selected loader's narrow server package receives its one mixin. */
public final class ServerMixinPlugin implements IMixinConfigPlugin {
    private final UniversalEnvironment.Selection selection;
    private UniversalEnvironment.Binding family;
    public ServerMixinPlugin() { this(UniversalEnvironment.current()); }
    public ServerMixinPlugin(UniversalEnvironment.Selection selection) { this.selection = Objects.requireNonNull(selection); }
    @Override public void onLoad(String packageName) {
        var next = UniversalEnvironment.binding(packageName, false);
        if (family != null && !family.equals(next)) throw new BootstrapFailure(Code.DISPATCH_CHANGED);
        family = next;
    }
    private UniversalEnvironment.Binding binding() {
        if (family == null) throw new BootstrapFailure(Code.DISPATCH_UNBOUND);
        return family;
    }
    @Override public String getRefMapperConfig() {
        return UniversalEnvironment.refmap(selection, binding(), false);
    }
    @Override public List<String> getMixins() { return UniversalEnvironment.serverMixins(selection, binding()); }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
