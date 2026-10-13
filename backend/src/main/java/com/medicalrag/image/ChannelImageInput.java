package com.medicalrag.image;
import javax.imageio.stream.ImageInputStreamImpl;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
/** Bounded random access: no full-file heap copy and no exposed filesystem path. */
final class ChannelImageInput extends ImageInputStreamImpl {
    private final SeekableByteChannel channel;
    ChannelImageInput(SeekableByteChannel channel) { this.channel=channel; }
    @Override public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)==-1 ? -1 : one[0]&255; }
    @Override public int read(byte[] bytes,int offset,int length) throws IOException {
        checkClosed(); java.util.Objects.checkFromIndexSize(offset,length,bytes.length); bitOffset=0;
        if (length==0) return 0;
        channel.position(streamPos); int read=channel.read(ByteBuffer.wrap(bytes,offset,length));
        if (read>0) streamPos+=read; return read;
    }
    @Override public long length() { try { return channel.size(); } catch (IOException e) { return -1; } }
    // The A5 inspection owns and closes the channel, including exceptional exits.
}
