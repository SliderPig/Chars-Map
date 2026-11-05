package com.example.charmap;

import com.example.charmap.net.Net;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A fast minimap and world map. How it stays fast is written up in README.md.
@Mod(CharMap.MODID)
public class CharMap {
    public static final String MODID = "charmap";
    public static final Logger LOG = LoggerFactory.getLogger("CharMap");

    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    public CharMap() {
        Net.register();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> com.example.charmap.client.MapClient::setup);
    }

    // A map must never take the game down, or fill the log: each kind of trouble is written down once and the map carries on.
    public static void once(String kind, String message, Throwable t) {
        if (SAID.add(kind)) LOG.warn("{} (only saying this once)", message, t);
    }
}
