```java
package net.minecraft.client.multiplayer;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Lists;

import net.lax1dude.eaglercraft.EagRuntime;
import net.lax1dude.eaglercraft.EaglerInputStream;
import net.lax1dude.eaglercraft.EaglerOutputStream;
import net.lax1dude.eaglercraft.internal.EnumServerRateLimit;
import net.lax1dude.eaglercraft.internal.QueryResponse;
import net.lax1dude.eaglercraft.socket.AddressResolver;
import net.lax1dude.eaglercraft.socket.RateLimitTracker;
import net.lax1dude.eaglercraft.socket.ServerQueryDispatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextFormatting;

/**
 * Customized Eaglercraft server list.
 * The built-in server is SpyderSammy Survival.
 */
public class ServerList {
    private static final Logger logger = LogManager.getLogger();

    private static final String DEFAULT_SERVER_NAME = "SpyderSammy Survival";
    private static final String DEFAULT_SERVER_ADDRESS = "wss://sv-survival.eagler.host/";

    private final Minecraft mc;
    private final List<ServerData> allServers = Lists.newArrayList();
    private final List<ServerData> servers = Lists.newArrayList();

    private static ServerList instance = null;

    private ServerList(Minecraft mcIn) {
        this.mc = mcIn;
        this.loadServerList();
    }

    public static void initServerList(Minecraft mc) {
        instance = new ServerList(mc);
    }

    public static ServerList getServerList() {
        return instance;
    }

    public void loadServerList() {
        loadServerList(EagRuntime.getStorage("s"));
    }

    public void loadServerList(byte[] localStorage) {
        try {
            freeServerIcons();
            this.allServers.clear();

            // Always add the custom built-in server.
            ServerData defaultServer = new ServerData(
                    DEFAULT_SERVER_NAME,
                    DEFAULT_SERVER_ADDRESS,
                    true
            );
            defaultServer.isDefault = true;
            defaultServer.hideAddress = false;
            this.allServers.add(defaultServer);

            // Load user-saved servers separately.
            if (localStorage != null) {
                NBTTagCompound compound = CompressedStreamTools
                        .readCompressed(new EaglerInputStream(localStorage));

                if (compound != null) {
                    NBTTagList serverList = compound.getTagList("servers", 10);

                    for (int i = 0; i < serverList.tagCount(); ++i) {
                        ServerData savedServer = ServerData.getServerDataFromNBTCompound(
                                serverList.getCompoundTagAt(i)
                        );
                        this.allServers.add(savedServer);
                    }
                }
            }
        } catch (Exception exception) {
            logger.error("Couldn't load server list", exception);
        } finally {
            refreshServerPing();
        }
    }

    public void saveServerList() {
        byte[] data = writeServerList();
        if (data != null) {
            EagRuntime.setStorage("s", data);
        }
    }

    public byte[] writeServerList() {
        try {
            NBTTagList serverList = new NBTTagList();

            for (int i = 0, length = this.servers.size(); i < length; ++i) {
                ServerData server = this.servers.get(i);

                if (!server.isDefault) {
                    serverList.appendTag(server.getNBTCompound());
                }
            }

            NBTTagCompound compound = new NBTTagCompound();
            compound.setTag("servers", serverList);

            EaglerOutputStream output = new EaglerOutputStream();
            CompressedStreamTools.writeCompressed(compound, output);
            return output.toByteArray();
        } catch (Exception exception) {
            logger.error("Couldn't save server list", exception);
            return null;
        }
    }

    public ServerData getServerData(int index) {
        return this.servers.get(index);
    }

    public void removeServerData(int index) {
        ServerData data = this.servers.remove(index);

        if (data != null && data.iconTextureObject != null) {
            mc.getTextureManager().deleteTexture(data.iconResourceLocation);
            data.iconTextureObject = null;
        }
    }

    public void addServerData(ServerData server) {
        this.servers.add(server);
    }

    public int countServers() {
        return this.servers.size();
    }

    public void swapServers(int first, int second) {
        ServerData server = this.getServerData(first);
        this.servers.set(first, this.getServerData(second));
        this.servers.set(second, server);
        this.saveServerList();
    }

    public void func_147413_a(int index, ServerData server) {
        this.servers.set(index, server);
    }

    public static void func_147414_b(ServerData server) {
        ServerList list = new ServerList(Minecraft.getMinecraft());
        list.loadServerList();

        for (int i = 0; i < list.countServers(); ++i) {
            ServerData existing = list.getServerData(i);

            if (existing.serverName.equals(server.serverName)
                    && existing.serverIP.equals(server.serverIP)) {
                list.func_147413_a(i, server);
                break;
            }
        }

        list.saveServerList();
    }

    public void freeServerIcons() {
        TextureManager manager = mc.getTextureManager();

        for (int i = 0, length = allServers.size(); i < length; ++i) {
            ServerData server = allServers.get(i);

            if (server.iconTextureObject != null) {
                manager.deleteTexture(server.iconResourceLocation);
                server.iconTextureObject = null;
            }
        }
    }

    public void refreshServerPing() {
        this.servers.clear();
        this.servers.addAll(this.allServers);

        for (int i = 0, length = this.servers.size(); i < length; ++i) {
            ServerData server = this.servers.get(i);

            if (server.currentQuery != null) {
                if (server.currentQuery.isOpen()) {
                    server.currentQuery.close();
                }
                server.currentQuery = null;
            }

            server.hasPing = false;
            server.pingSentTime = -1L;
        }
    }

    public void updateServerPing() {
        int total = 0;

        for (int i = 0, length = this.servers.size(); i < length; ++i) {
            ServerData server = this.servers.get(i);

            if (server.pingSentTime <= 0L) {
                server.pingSentTime = EagRuntime.steadyTimeMillis();

                if (RateLimitTracker.isLockedOut(server.serverIP)) {
                    logger.error(
                            "Server {} locked this client out on a previous connection, will not attempt to reconnect",
                            server.serverIP
                    );
                    server.serverMOTD = TextFormatting.RED
                            + "Too Many Requests!\nTry again later";
                    server.pingToServer = -1L;
                    server.hasPing = true;
                    server.pinged = true;
                } else {
                    server.pingToServer = -2L;

                    String address = AddressResolver.resolveURI(server.serverIP);
                    server.currentQuery = ServerQueryDispatch.sendServerQuery(address, "MOTD");

                    if (server.currentQuery == null) {
                        server.pingToServer = -1L;
                        server.hasPing = true;
                        server.pinged = true;
                    } else {
                        ++total;
                    }
                }
            } else if (server.currentQuery != null) {
                server.currentQuery.update();

                if (!server.hasPing) {
                    ++total;

                    EnumServerRateLimit rateLimit = server.currentQuery.getRateLimit();

                    if (rateLimit != EnumServerRateLimit.OK) {
                        if (rateLimit == EnumServerRateLimit.BLOCKED) {
                            RateLimitTracker.registerBlock(server.serverIP);
                        } else if (rateLimit == EnumServerRateLimit.LOCKED_OUT) {
                            RateLimitTracker.registerLockOut(server.serverIP);
                        }

                        server.serverMOTD = TextFormatting.RED
                                + "Too Many Requests!\nTry again later";
                        server.pingToServer = -1L;
                        server.hasPing = true;
                        return;
                    }
                }

                if (server.currentQuery.responsesAvailable() > 0) {
                    QueryResponse response;

                    do {
                        response = server.currentQuery.getResponse();
                    } while (server.currentQuery.responsesAvailable() > 0);

                    if (response.responseType.equalsIgnoreCase("MOTD")
                            && response.isResponseJSON()) {
                        server.setMOTDFromQuery(response);

                        if (!server.hasPing) {
                            server.pingToServer =
                                    response.clientTime - server.pingSentTime;
                            server.hasPing = true;
                        }
                    }
                }

                if (server.currentQuery.binaryResponsesAvailable() > 0) {
                    byte[] response;

                    do {
                        response = server.currentQuery.getBinaryResponse();
                    } while (server.currentQuery.binaryResponsesAvailable() > 0);

                    server.setIconPacket(response);
                }

                if (!server.currentQuery.isOpen()
                        && server.pingSentTime > 0L
                        && (EagRuntime.steadyTimeMillis()
                                - server.pingSentTime) > 2000L
                        && !server.hasPing) {

                    if (RateLimitTracker.isProbablyLockedOut(server.serverIP)) {
                        logger.error(
                                "Server {} ratelimited this client out on a previous connection, assuming lockout",
                                server.serverIP
                        );
                        server.serverMOTD = TextFormatting.RED
                                + "Too Many Requests!\nTry again later";
                    }

                    server.pingToServer = -1L;
                    server.hasPing = true;
                }
            }

            if (total >= 4) {
                break;
            }
        }
    }
}
```
