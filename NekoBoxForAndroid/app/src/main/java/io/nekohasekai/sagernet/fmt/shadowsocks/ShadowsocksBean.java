package io.nekohasekai.sagernet.fmt.shadowsocks;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class ShadowsocksBean extends AbstractBean {

    public String method;
    public String password;
    public String plugin;

    public Boolean sUoT;

    // --------------------------------------- Mux（sing-box multiplex，服务端需支持）

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;     // 0:h2mux 1:smux 2:yamux（SS 用 smux/yamux）
    public Integer muxConcurrency;
    public Integer muxMode;     // 0: max_streams 1: connections
    public Integer muxMaxConnections;
    public Integer muxMinStreams;
    public Boolean muxBrutal;
    public Integer muxBrutalUpMbps;
    public Integer muxBrutalDownMbps;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(method)) method = "aes-256-gcm";
        if (method == null) method = "";
        if (password == null) password = "";
        if (plugin == null) plugin = "";
        if (sUoT == null) sUoT = false;

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 1;
        if (muxConcurrency == null) muxConcurrency = 1;
        if (muxMode == null) muxMode = 0;
        if (muxMaxConnections == null) muxMaxConnections = 4;
        if (muxMinStreams == null) muxMinStreams = 0;
        if (muxBrutal == null) muxBrutal = false;
        if (muxBrutalUpMbps == null) muxBrutalUpMbps = 0;
        if (muxBrutalDownMbps == null) muxBrutalDownMbps = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeString(method);
        output.writeString(password);
        output.writeString(plugin);
        output.writeBoolean(sUoT);

        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);
        output.writeInt(muxMode);
        output.writeInt(muxMaxConnections);
        output.writeInt(muxMinStreams);
        output.writeBoolean(muxBrutal);
        output.writeInt(muxBrutalUpMbps);
        output.writeInt(muxBrutalDownMbps);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        method = input.readString();
        password = input.readString();
        plugin = input.readString();
        sUoT = input.readBoolean();

        if (version >= 3) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
            muxMode = input.readInt();
            muxMaxConnections = input.readInt();
            muxMinStreams = input.readInt();
            muxBrutal = input.readBoolean();
            muxBrutalUpMbps = input.readInt();
            muxBrutalDownMbps = input.readInt();
        }
    }

    @NotNull
    @Override
    public ShadowsocksBean clone() {
        return KryoConverters.deserialize(new ShadowsocksBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowsocksBean> CREATOR = new CREATOR<ShadowsocksBean>() {
        @NonNull
        @Override
        public ShadowsocksBean newInstance() {
            return new ShadowsocksBean();
        }

        @Override
        public ShadowsocksBean[] newArray(int size) {
            return new ShadowsocksBean[size];
        }
    };
}
