package com.medicalrag.document;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadView;

/** Bounded 8 KiB read cache; PDFBox never receives a disk path or a whole-file byte array. */
final class ChannelPdfReader implements RandomAccessRead {
    private final FileChannel channel;
    private final byte[] cache = new byte[8192];
    private long position, cacheStart=-1;
    private int cached;
    private boolean closed;
    ChannelPdfReader(FileChannel channel) { this.channel=channel; }
    private void open() throws IOException { if (closed || !channel.isOpen()) throw new IOException("PDF reader closed"); }
    private void load() throws IOException {
        open(); long start=(position/cache.length)*cache.length;
        if (cacheStart==start) return;
        ByteBuffer bytes=ByteBuffer.wrap(cache); int count;
        while (bytes.hasRemaining() && (count=channel.read(bytes,start+bytes.position()))!=-1) { if (count==0) break; }
        cached=bytes.position(); cacheStart=start;
    }
    @Override public int read() throws IOException {
        if (isEOF()) return -1; load(); return cache[(int)(position++-cacheStart)] & 255;
    }
    @Override public int read(byte[] bytes,int offset,int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset,length,bytes.length); open(); if (length==0) return 0;
        if (isEOF()) return -1; int total=0;
        while (total<length && !isEOF()) {
            load(); int index=(int)(position-cacheStart), count=Math.min(length-total,cached-index);
            if (count<=0) throw new IOException("PDF source changed during validation");
            System.arraycopy(cache,index,bytes,offset+total,count); position+=count; total+=count;
        }
        return total;
    }
    @Override public long getPosition() throws IOException { open(); return position; }
    @Override public void seek(long next) throws IOException { open(); if (next<0) throw new IOException("Negative PDF position"); position=next; }
    @Override public long length() throws IOException { open(); return channel.size(); }
    @Override public boolean isClosed() { return closed; }
    @Override public boolean isEOF() throws IOException { return position>=length(); }
    @Override public RandomAccessReadView createView(long start,long length) throws IOException {
        open(); if (start<0 || length<0 || start>length() || length>length()-start) throw new IOException("Invalid PDF view");
        return new RandomAccessReadView(this,start,length);
    }
    @Override public void close() { closed=true; } // A5 owns and closes the actual file channel.
}
