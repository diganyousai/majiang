package cn.qingzhu;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Opaque per-player credentials; no room passwords or hand data are stored. */
public final class PlayerIdentityStore {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private static final class UserStore {
        static final PlayerIdentityStore INSTANCE=new PlayerIdentityStore(Path.of(
            System.getenv("LOCALAPPDATA")==null?System.getProperty("user.home"):System.getenv("LOCALAPPDATA"),
            "QingzhuMahjongOnline","identities"));
    }
    private final Path folder;
    private final Map<String,String> memory=new ConcurrentHashMap<>();
    public PlayerIdentityStore(Path folder) { this.folder=folder==null?null:folder.toAbsolutePath().normalize(); }
    public static PlayerIdentityStore user() { return UserStore.INSTANCE; }
    public static PlayerIdentityStore memory() { return new PlayerIdentityStore(null); }
    public String player(String nickname) {
        String name=nickname.trim();
        if(name.isEmpty()) throw new IllegalArgumentException("请填写玩家昵称");
        if(folder==null) return memory.computeIfAbsent(name,ignored->newIdentity());
        synchronized(LOCKS.computeIfAbsent(folder,ignored->new Object())) {
            try {
                Files.createDirectories(folder);
                try(FileChannel channel=FileChannel.open(folder.resolve("identities.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                    FileLock lock=channel.lock()) {
                    String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)));
                    Path target=folder.resolve(key+".id");
                    if(Files.exists(target)) {
                        String id=Files.readString(target,StandardCharsets.UTF_8).trim();
                        if(!id.matches("player-[A-Za-z0-9_-]{32}")) throw new IOException("身份文件损坏，请保留文件并联系房主重新建房");
                        return id;
                    }
                    String id=newIdentity();
                    Files.writeString(target,id,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    return id;
                }
            } catch(IOException|NoSuchAlgorithmException e) { throw new IllegalStateException("无法读取本机玩家身份："+e.getMessage(),e); }
        }
    }
    private static String newIdentity() {
        byte[] bytes=new byte[24]; RANDOM.nextBytes(bytes);
        return "player-"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
