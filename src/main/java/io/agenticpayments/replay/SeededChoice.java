package io.agenticpayments.replay;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Versioned named choice; only plan expansion calls this, never runtime worker selection. */
public final class SeededChoice {
    public static final String VERSION="plan-choice-0.1";
    private SeededChoice() {}
    public static int index(String seed,int ordinal,String name,int candidateCount) {
        if(!Long.toString(Long.parseLong(seed)).equals(seed)||ordinal<1||candidateCount<1||name==null||!name.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid named choice");
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest((VERSION+'\0'+seed+'\0'+ordinal+'\0'+name).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1,Arrays.copyOf(hash,8)).mod(BigInteger.valueOf(candidateCount)).intValueExact();
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static <T>T choose(String seed,int ordinal,String name,List<T> candidates) { return candidates.get(index(seed,ordinal,name,candidates.size())); }
}
