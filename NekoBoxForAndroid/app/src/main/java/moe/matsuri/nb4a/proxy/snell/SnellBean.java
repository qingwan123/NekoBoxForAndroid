package moe.matsuri.nb4a.proxy.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

/**
 * Snell v4 / v6
 * <p>
 * sing-box 1.14: version 4 使用 obfs_mode / obfs_host，version 6 使用 mode / userkey / reuse。
 */
public class SnellBean extends AbstractBean {

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };

    public String psk;
    public Integer version;
    public String obfsMode;
    public String obfsHost;
    public String mode;
    public String userKey;
    public Boolean reuse;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (psk == null) psk = "";
        if (version == null) version = 4;
        if (obfsMode == null) obfsMode = "none";
        if (obfsHost == null) obfsHost = "";
        if (mode == null) mode = "default";
        if (userKey == null) userKey = "";
        if (reuse == null) reuse = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(psk);
        output.writeInt(version);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeString(mode);
        output.writeString(userKey);
        output.writeBoolean(reuse);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int serializationVersion = input.readInt();
        super.deserialize(input);
        if (serializationVersion >= 0) {
            psk = input.readString();
            version = input.readInt();
            obfsMode = input.readString();
            obfsHost = input.readString();
            mode = input.readString();
            userKey = input.readString();
            reuse = input.readBoolean();
        }
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }
}
