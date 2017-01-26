package org.ethereum.mine;

import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.crypto.HashUtil;
import org.spongycastle.util.Arrays;
import sun.misc.Unsafe;
import sun.nio.ch.DirectBuffer;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Random;

import static java.lang.System.arraycopy;
import static java.lang.System.in;
import static java.math.BigInteger.valueOf;
import static org.ethereum.crypto.HashUtil.sha3;
import static org.ethereum.util.ByteUtil.*;
import static org.spongycastle.util.Arrays.reverse;

/**
 * The Ethash algorithm described in https://github.com/ethereum/wiki/wiki/Ethash
 *
 * Created by Anton Nashatyrev on 27.11.2015.
 */
public class EthashAlgo {
    EthashParams params;

    public EthashAlgo() {
        this(new EthashParams());
    }

    public EthashAlgo(EthashParams params) {
        this.params = params;
    }

    public EthashParams getParams() {
        return params;
    }

    // Little-Endian !
    static int getWord(byte[] arr, int wordOff) {
        return ByteBuffer.wrap(arr, wordOff * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static void setWord(byte[] arr, int wordOff, long val) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) val);
        bb.rewind();
        bb.get(arr, wordOff * 4, 4);
    }

    public static int remainderUnsigned(int dividend, int divisor) {
        if (divisor >= 0) {
            if (dividend >= 0) {
                return dividend % divisor;
            }
            // The implementation is a Java port of algorithm described in the book
            // "Hacker's Delight" (section "Unsigned short division from signed division").
            int q = ((dividend >>> 1) / divisor) << 1;
            dividend -= q * divisor;
            if (dividend < 0 || dividend >= divisor) {
                dividend -= divisor;
            }
            return dividend;
        }
        return dividend >= 0 || dividend < divisor ? dividend : dividend - divisor;
    }


    private byte[][] makeCacheBytes(long cacheSize, byte[] seed) {
        int n = (int) (cacheSize / params.getHASH_BYTES());
        byte[][] o = new byte[n][];
        o[0] = HashUtil.sha512(seed);
        for (int i = 1; i < n; i++) {
            o[i] = HashUtil.sha512(o[i - 1]);
        }

        for (int cacheRound = 0; cacheRound < params.getCACHE_ROUNDS(); cacheRound++) {
            for (int i = 0; i < n; i++) {
                int v = remainderUnsigned(getWord(o[i], 0), n);
                o[i] = HashUtil.sha512(xor(o[(i - 1 + n) % n], o[v]));
            }
        }
        return o;
    }

    public int[] makeCache(long cacheSize, byte[] seed) {
        byte[][] bytes = makeCacheBytes(cacheSize, seed);
        int[] ret = new int[bytes.length * bytes[0].length / 4];
        int[] ints = new int[bytes[0].length / 4];
        for (int i = 0; i < bytes.length; i++) {
            bytesToInts(bytes[i], ints, false);
            arraycopy(ints, 0, ret, i * ints.length, ints.length);
        }
        return ret;
    }

    private static final int FNV_PRIME = 0x01000193;
    private static int fnv(int v1, int v2) {
        return (v1 * FNV_PRIME) ^ v2;
    }

    int[] sha512(int[] arr, boolean bigEndian) {
        byte[] bytesTmp = new byte[arr.length << 2];
        intsToBytes(arr, bytesTmp, bigEndian);
        bytesTmp = HashUtil.sha512(bytesTmp);
        bytesToInts(bytesTmp, arr, bigEndian);
        return arr;
    }

    public final int[] calcDatasetItem(final int[] cache, final int i) {
        final int r = params.getHASH_BYTES() / params.getWORD_BYTES();
        final int n = cache.length / r;
        int[] mix = Arrays.copyOfRange(cache, i % n * r, (i % n + 1) * r);

        mix[0] = i ^ mix[0];
        mix = sha512(mix, false);
        final int dsParents = (int) params.getDATASET_PARENTS();
        final int mixLen = mix.length;
        for (int j = 0; j < dsParents; j++) {
            int cacheIdx = fnv(i ^ j, mix[j % r]);
            cacheIdx = remainderUnsigned(cacheIdx, n);
            int off = cacheIdx * r;
            for (int k = 0; k < mixLen; k++) {
                mix[k] = fnv(mix[k], cache[off + k]);
            }
        }
        return sha512(mix, false);
    }

    public ByteBuffer calcDataset(long fullSize, int[] cache) {
        ByteBuffer buf = ByteBuffer.allocateDirect((int) fullSize);
        long addr = ((DirectBuffer) buf).address();
        Unsafe unsafe = getUnsafe();
        int hashesCount = (int) (fullSize / params.getHASH_BYTES());
        for (int i = 0; i < hashesCount; i++) {
            int[] item = calcDatasetItem(cache, i);
            long pos = addr + i * (params.getHASH_BYTES());
            for (int i1 = 0; i1 < item.length; i1++) {
                unsafe.putInt(pos + (i1 << 2), item[i1]);
            }
        }
        return buf;
    }

    private static Unsafe getUnsafe() {
        try {

            Field singleoneInstanceField = Unsafe.class.getDeclaredField("theUnsafe");
            singleoneInstanceField.setAccessible(true);
            return (Unsafe) singleoneInstanceField.get(null);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public Pair<byte[], byte[]> hashimoto(byte[] blockHeaderTruncHash, byte[] nonce, long fullSize,
                                          int[] cache, long fullDagAddr, boolean full) {
        Unsafe unsafe = getUnsafe();
        if (nonce.length != 8) throw new RuntimeException("nonce.length != 8");

        int hashWords = params.getHASH_BYTES() / 4;
        int w = params.getMIX_BYTES() / params.getWORD_BYTES();
        int mixhashes = params.getMIX_BYTES() / params.getHASH_BYTES();
        int[] s = bytesToInts(HashUtil.sha512(merge(blockHeaderTruncHash, reverse(nonce))), false);
        int[] mix = new int[params.getMIX_BYTES() / 4];
        for (int i = 0; i < mixhashes; i++) {
            arraycopy(s, 0, mix, i * s.length, s.length);
        }

        int numFullPages = (int) (fullSize / params.getMIX_BYTES());
        if (!full) {
            for (int i = 0; i < params.getACCESSES(); i++) {
                int p = remainderUnsigned(fnv(i ^ s[0], mix[i % w]), numFullPages);
                int[] newData = new int[mix.length];
                int off = p * mixhashes;
                for (int j = 0; j < mixhashes; j++) {
                    int itemIdx = off + j;

                    int[] lookup1 = calcDatasetItem(cache, itemIdx);
                    arraycopy(lookup1, 0, newData, j * lookup1.length, lookup1.length);
                }
                for (int i1 = 0; i1 < mix.length; i1++) {
                    mix[i1] = fnv(mix[i1], newData[i1]);
                }
            }
        } else {
            int s0 = s[0];
            for (int i = 0; i < 64; i++) {
                int p = remainderUnsigned(fnv(i ^ s0, mix[i & 0x1F]), numFullPages);
                int off = p << 5;

                long addr = fullDagAddr + (off << 2);
                for (int i1 = 0; i1 < 32; i1++, addr += 4) {
                    mix[i1] = fnv(mix[i1], unsafe.getInt(addr));
                }
            }
        }

        int[] cmix = new int[mix.length / 4];
        for (int i = 0; i < mix.length; i += 4 /* ? */) {
            int fnv1 = fnv(mix[i], mix[i + 1]);
            int fnv2 = fnv(fnv1, mix[i + 2]);
            int fnv3 = fnv(fnv2, mix[i + 3]);
            cmix[i >> 2] = fnv3;
        }

        return Pair.of(intsToBytes(cmix, false), sha3(merge(intsToBytes(s, false), intsToBytes(cmix, false))));
    }

    public Pair<byte[], byte[]> hashimotoLight(long fullSize, final int[] cache, byte[] blockHeaderTruncHash,
                                               byte[]  nonce) {
        return hashimoto(blockHeaderTruncHash, nonce, fullSize, cache, 0, false);
    }

    public Pair<byte[], byte[]> hashimotoFull(long fullSize, final long dataset, byte[] blockHeaderTruncHash,
                                              byte[]  nonce) {
        return hashimoto(blockHeaderTruncHash, nonce, fullSize, null, dataset, true);
    }

    public long mine(long fullSize, final long dataset, byte[] blockHeaderTruncHash, long difficulty) {
        return mine(fullSize, dataset, blockHeaderTruncHash, difficulty, new Random().nextLong());
    }

    public long mine(long fullSize, final long dataset, byte[] blockHeaderTruncHash, long difficulty, long startNonce) {
        long nonce = startNonce;
        BigInteger target = valueOf(2).pow(256).divide(valueOf(difficulty));
        long start = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            nonce++;
            Pair<byte[], byte[]> pair = hashimotoFull(fullSize, dataset, blockHeaderTruncHash, longToBytes(nonce));
            BigInteger h = new BigInteger(1, pair.getRight() /* ?? */);
            if (h.compareTo(target) < 0) break;
        }

        long hashes = nonce - startNonce;
        long calcTimeUsec = (System.nanoTime() - start) / 1000;
        long hashrate = hashes * 1_000_000 / calcTimeUsec;
        System.out.println("Hashrate: " + hashrate + " H/s");

        return nonce;
    }

    /**
     * This the slower miner version which uses only cache thus taking much less memory than
     * regular {@link #mine} method
     */
    public long mineLight(long fullSize, final int[] cache, byte[] blockHeaderTruncHash, long difficulty) {
        return mineLight(fullSize, cache, blockHeaderTruncHash, difficulty, new Random().nextLong());
    }

    public long mineLight(long fullSize, final int[] cache, byte[] blockHeaderTruncHash, long difficulty, long startNonce) {
        long nonce = startNonce;
        BigInteger target = valueOf(2).pow(256).divide(valueOf(difficulty));
        while(!Thread.currentThread().isInterrupted()) {
            nonce++;
            Pair<byte[], byte[]> pair = hashimotoLight(fullSize, cache, blockHeaderTruncHash, longToBytes(nonce));
            BigInteger h = new BigInteger(1, pair.getRight() /* ?? */);
            if (h.compareTo(target) < 0) break;
        }
        return nonce;
    }

    public byte[] getSeedHash(long blockNumber) {
        byte[] ret = new byte[32];
        for (int i = 0; i < blockNumber / params.getEPOCH_LENGTH(); i++) {
            ret = sha3(ret);
        }
        return ret;
    }
}
