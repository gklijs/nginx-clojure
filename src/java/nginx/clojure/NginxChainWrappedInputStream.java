/**
 *  Copyright (C) Zhang,Yuexiang (xfeep)
 *
 */
package nginx.clojure;

import static nginx.clojure.MiniConstants.NGX_CLOJURE_BUF_FILE_FLAG;
import static nginx.clojure.MiniConstants.NGX_CLOJURE_BUF_FLUSH_FLAG;
import static nginx.clojure.MiniConstants.NGX_CLOJURE_BUF_LAST_FLAG;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NginxChainWrappedInputStream extends InputStream {
	
	protected NginxRequest r;
	protected long chain;
	protected int index;
	protected InputStream[] streams;
	protected int flag;

	public static class RangeSeekableFileInputStream extends InputStream {

		protected final RandomAccessFile file;
		protected final long start;
		protected long pos;
		protected final long length;
		
		public RangeSeekableFileInputStream() {
			file = null;
			start = 0;
			length = pos = 0;
		}
		
		public RangeSeekableFileInputStream(String file, long pos, long len) throws IOException {
			this.file = new RandomAccessFile(file, "r");
			this.file.seek(pos);
			this.start = this.pos = pos;
			this.length = len;
		}
		
		@Override
		public int read() throws IOException {
			if (pos == length) {
				return -1;
			}
			
			pos++;
			return file.read();
		}
		
		/* (non-Javadoc)
		 * @see java.io.InputStream#read(byte[], int, int)
		 */
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (pos == length) {
				return -1;
			}
			
			if (len == 0) {
				return 0;
			}
			
			if (pos + len >= length) {
				len = (int)(length - pos);
			}
			
			len = file.read(b, off, len);
			pos += len;
			return len;
		}
		
	}
	
	public NginxChainWrappedInputStream() {
		this.chain = 0;
	}
	
	public NginxChainWrappedInputStream(NginxRequest r, long chain) throws IOException {
		this.r = r;
		this.chain = chain;
		
		while (chain != 0) {
			ByteBuffer buf = NginxClojureRT.pickByteBuffer();
			chain = NginxClojureRT.ngx_http_clojure_mem_get_chain_info(chain, buf.array(), MiniConstants.BYTE_ARRAY_OFFSET, buf.remaining());
			buf.limit(buf.capacity());
			if (chain < 0) {
				throw new RuntimeException("Invalid request and chain: { chain=" + this.chain + ", request:" + r + ", rc=" + chain + "}");
			}
			
			buf.order(ByteOrder.nativeOrder());
			int streamsLen = (int)buf.getLong();
			int streamsPos = 0;
			if (streams == null) {
				streams = new InputStream[streamsLen];
			}else {
				streamsPos = streams.length;
				InputStream[] newStreams = new InputStream[streamsPos + streamsLen];
				System.arraycopy(streams, 0, newStreams, 0, streamsPos);
				streams = newStreams;
			}
			
			while (streamsPos < streams.length) {
				long typeAndLen = buf.getLong();
				long addr = buf.getLong();
				int type = (int)(typeAndLen >> 56);
				long len = typeAndLen & 0x00ffffffffffffffL;
				
				if ( (type & NGX_CLOJURE_BUF_FILE_FLAG) != 0) {
					ByteBuffer fileNameBuf = buf.slice();
					fileNameBuf.limit((int)(addr >> 48));
					String file = HackUtils.decode(fileNameBuf, MiniConstants.DEFAULT_ENCODING, NginxClojureRT.pickCharBuffer());
					streams[streamsPos++] = new RangeSeekableFileInputStream(file, addr & 0x0000ffffffffffffL, len);
				}else {
					streams[streamsPos++] = new NativeInputStream(addr, len);
				}
				
				if ( (type & NGX_CLOJURE_BUF_LAST_FLAG) != 0) {
					flag |= NGX_CLOJURE_BUF_LAST_FLAG;
				}
				
				if ( (type & NGX_CLOJURE_BUF_FLUSH_FLAG) != 0) {
					flag |= NGX_CLOJURE_BUF_FLUSH_FLAG;
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see java.io.InputStream#read()
	 */
	@Override
	public int read() throws IOException {
		if (chain == 0 || index >= streams.length) {
			return -1;
		}
		
		int c = streams[index].read();
		
		while (c == -1 && ++index < streams.length) {
			c = streams[index].read();
		}
		return c;
	}
	
	/* (non-Javadoc)
	 * @see java.io.InputStream#read(byte[], int, int)
	 */
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (chain == 0 || index >= streams.length) {
			return -1;
		}
		
		if (len == 0) {
			return 0;
		}
		
		int c = streams[index].read(b, off, len);
		
		while (c <= 0 && ++index < streams.length) {
			c = streams[index].read(b, off, len);
		}
		return c;
	}

	public long nativeChain() {
		return chain;
	}
	
	public NginxRequest getRquest() {
		return r;
	}
	
	public boolean isLast() {
		return  (flag & NGX_CLOJURE_BUF_LAST_FLAG) != 0;
	}
}