package dev.keneq.dsm;

import com.mojang.logging.LogUtils;
import dev.keneq.dsm.client.DsmClientEvents;
import dev.keneq.dsm.config.DsmClientConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;

@Mod(DsmMod.MOD_ID)
public final class DsmMod {
    public static final String MOD_ID = "dsm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DsmMod() {
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true)
        );
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, DsmClientConfig.SPEC, "dsm-client.toml");

        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> DsmClientEvents::init);
    }
}