/*
 * OWASP Enterprise Security API (ESAPI)
 * 
 * This file is part of the Open Web Application Security Project (OWASP)
 * Enterprise Security API (ESAPI) project. For details, please see
 * <a href="http://www.owasp.org/index.php/ESAPI">http://www.owasp.org/index.php/ESAPI</a>.
 *
 * Copyright (c) 2009 - The OWASP Foundation
 */
package org.owasp.esapi.crypto;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.owasp.esapi.ESAPI;
import org.owasp.esapi.Logger;
import org.owasp.esapi.errors.EncryptionException;

/**
 * Class to provide some convenience methods for encryption, decryption, etc.
 * </p><p>
 * All the cryptographic operations use the default cryptographic properties;
 * e.g., default cipher transformation, default key size, default IV type (where
 * applicable), etc.
 * 
 * @author kevin.w.wall@gmail.com
 * @since 2.0
 */
public class CryptoHelper {
	
	private static final Logger logger = ESAPI.getLogger("CryptoHelper");

	// TODO: Also consider supplying implementation of RFC 2898 / PKCS#5 PBKDF2
	//		 in this file as well??? Maybe save for ESAPI 2.1 or 3.0.
	/**
	 * Generate a random secret key appropriate to the specified cipher algorithm
	 * and key size.
	 * @param alg	The cipher algorithm or cipher transformation. (If the latter is
	 * 				passed, the cipher algorithm is determined from it.)
	 * @param keySize	The key size, in bits.
	 * @return	A random {@code SecretKey} is returned.
	 * @throws EncryptionException Thrown if cannot create secret key conforming to
	 * 				requested algorithm with requested size.
	 */
	public static SecretKey generateSecretKey(String alg, int keySize)
		throws EncryptionException
	{
		assert( keySize > 0 );	// Usually should be even multiple of 8, but not strictly required by alg.
		
		// Don't use CipherSpec here to get algorithm as this may cause assertion
		// to fail (when enabled) if only algorithm name is passed to us.
		String[] cipherSpec = alg.split("/");
		String cipherAlg = cipherSpec[0];
		try {
		    // Special case for things like PBEWithMD5AndDES or PBEWithSHA1AndDESede.
		    // In such cases, the key generator should only request an instance of "PBE".
		    if ( cipherAlg.toUpperCase().startsWith("PBEWITH") ) {
		        cipherAlg = "PBE";
		    }
			KeyGenerator kgen =
				KeyGenerator.getInstance( cipherAlg );
			kgen.init(keySize);
			return kgen.generateKey();
		} catch (NoSuchAlgorithmException e) {
			throw new EncryptionException("Failed to generate random secret key",
					"Failed to generate secret key for " + alg + " with size of " + keySize + " bits.", e);
		}
	}

	// CHECKME: This method is critical and we can't afford to make changes to
	//          it after ESAPI 2.0 is released or users with encrypted data
	//		    using the new encryption / decryption may be forever screwed.
	//			(See CAUTION, below, for details.) Strongly suggest that we
	//			at least review this one class!!! (See Issue # 81.) This includes
    //          using HmacSHA1. If some other HMAC (e.g., HMAC-SHA256) needs to be used
    //          it MUST be done before 2.0 goes GA. (Note: Not sure that HMAC-SHA256
    //          is available in JDK 1.5 though.)
	/**
	 * Compute a derived key from the keyDerivationKey for either encryption / decryption
	 * or for authentication.
	 * <p>
	 * <b>CAUTION:</b> If this algorithm for computing derived keys from the
	 * key derivation key is <i>ever</i> changed, we risk breaking backward compatibility of being
	 * able to decrypt data previously encrypted with earlier / different versions
	 * of this method. Therefore, do not change this unless you are 100% certain that
	 * what you are doing will NOT change either of the derived keys for
	 * ANY "key derivation key" AT ALL!!!
	 * 
	 * @param keyDerivationKey  A key used as an input to a key derivation function
	 *                          to derive other keys. This is the key that generally
	 *                          is created using some key generation mechanism such as
	 *                          {@link #generateSecretKey(String, int)}.
	 * The "input" key from which the other keys are derived.
	 * 						The derived key will have the same algorithm type as this
	 * 						key.
	 * @param keySize		The cipher's key size (in bits) for the {@code keyDerivationKey}.
	 * 						Must have a minimum size of 56 bits and be an integral multiple of 8-bits.
	 * 						<b>Note:</b> The derived key will have the same size as this.
	 * @param purpose		The purpose for the derived key. Must be either the
	 * 						string "encryption" or "authenticity". Use "encryption" for
     *                      creating a derived key to use for confidentiality, and "authenticity"
     *                      for a derived key to use with a MAC to ensure message authenticity.
	 * @return				The derived {@code SecretKey} to be used according
	 * 						to the specified purpose.
	 * @throws NoSuchAlgorithmException		The {@code keyDerivationKey} has an unsupported
	 * 						encryption algorithm or no current JCE provider supports
	 * 						"HmacSHA1".
	 * @throws EncryptionException		If "UTF-8" is not supported as an encoding, then
	 * 						this is thrown with the original {@code UnsupportedEncodingException}
	 * 						as the cause. (NOTE: This should never happen as "UTF-8" is supposed to
	 * 						be a common encoding supported by all Java implementations. Support
	 * 					    for it is usually in rt.jar.)
	 * @throws InvalidKeyException 	Likely indicates a coding error. Should not happen.
	 * @throws EncryptionException 
	 */
	public static SecretKey computeDerivedKey(SecretKey keyDerivationKey, int keySize, String purpose)
			throws NoSuchAlgorithmException, InvalidKeyException, EncryptionException
	{
        // These really should be turned into actual runtime checks and an
        // IllegalArgumentException should be thrown if they are violated.
		assert keyDerivationKey != null : "Key derivation key cannot be null.";
			// We would choose a larger minimum key size, but we want to be
			// able to accept DES for legacy encryption needs.
		assert keySize >= 56 : "Key has size of " + keySize + ", which is less than minimum of 56-bits.";
		assert (keySize % 8) == 0 : "Key size (" + keySize + ") must be a even multiple of 8-bits.";
		assert purpose != null;
		assert purpose.equals("encryption") || purpose.equals("authenticity") :
			"Purpose must be \"encryption\" or \"authenticity\".";

		keySize = calcKeySize( keySize );	// Safely convert to whole # of bytes.
		byte[] derivedKey = new byte[ keySize ];
		byte[] tmpKey = null;
		byte[] inputBytes;
		try {
			inputBytes = purpose.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new EncryptionException("Encryption failure (internal encoding error: UTF-8)",
					 "UTF-8 encoding is NOT supported as a standard byte encoding: " + e.getMessage(), e);
		}
		
			// Note that keyDerivationKey is going to be some SecretKey like an AES or
			// DESede key, but not an HmacSHA1 key. That means it is not likely
			// going to be 20 bytes but something different. Experiments show
			// that doesn't really matter though as the SecretKeySpec CTOR on
			// the following line still returns the appropriate sized key for
			// HmacSHA1. So, if keyDerivationKey was originally (say) a 56-bit
            // DES key, then there is apparently some key-stretching going on here
            // under the hood to create 'sk' so that it is 20 bytes. I cannot vouch
            // for how secure this key-stretching is. Worse, it might not be specified
            // as to *how* it is done and left to each JCE provider.
		SecretKey sk = new SecretKeySpec(keyDerivationKey.getEncoded(), "HmacSHA1");
		Mac mac = null;

		try {
			mac = Mac.getInstance("HmacSHA1");
			mac.init(sk);
		} catch( InvalidKeyException ex ) {
			logger.error(Logger.SECURITY_FAILURE,
					"Created HmacSHA1 Mac but SecretKey sk has alg " +
					sk.getAlgorithm(), ex);
			throw ex;
		}

		// Repeatedly call of HmacSHA1 hash until we've collected enough bits
		// for the derived key. The first time through, we calculate the HmacSHA1
		// on the "purpose" string, but subsequent calculations are performed
		// on the previous result.
		int totalCopied = 0;
		int destPos = 0;
		int len = 0;
		do {
            // According to the Javadoc for Mac.doFinal(byte[]),
            // "A call to this method resets this Mac object to the state it was
            // in when previously initialized via a call to init(Key) or
            // init(Key, AlgorithmParameterSpec). That is, the object is reset
            // and available to generate another MAC from the same key, if
            // desired, via new calls to update and doFinal." Therefore, we do
            // not do an explicit reset().
			tmpKey = mac.doFinal(inputBytes);
			if ( tmpKey.length >= keySize ) {
				len = keySize;
			} else {
				len = Math.min(tmpKey.length, keySize - totalCopied);
			}
			System.arraycopy(tmpKey, 0, derivedKey, destPos, len);
			inputBytes = tmpKey;
			totalCopied += tmpKey.length;
			destPos += len;
		} while( totalCopied < keySize );
		
        // Convert it back into a SecretKey of the appropriate type.
		return new SecretKeySpec(derivedKey, keyDerivationKey.getAlgorithm());
	}

	/**
	 * Return true if specified cipher mode is one of those specified in the
	 * {@code ESAPI.properties} file that supports both confidentiality
	 * <b>and</b> authenticity (i.e., a "combined cipher mode" as NIST refers
	 * to it).
	 * @param cipherMode The specified cipher mode to be used for the encryption
	 *                   or decryption operation.
	 * @return true if the specified cipher mode is in the comma-separated list
	 *         of cipher modes supporting both confidentiality and authenticity;
	 *         otherwise false.
	 * @see org.owasp.esapi.SecurityConfiguration#getCombinedCipherModes()
	 */
	public static boolean isCombinedCipherMode(String cipherMode)
	{
	    assert cipherMode != null : "Cipher mode may not be null";
	    assert ! cipherMode.equals("") : "Cipher mode may not be empty string";
	    List<String> combinedCipherModes =
	        ESAPI.securityConfiguration().getCombinedCipherModes();
	    return combinedCipherModes.contains( cipherMode );
	}

	/**
     * Return true if specified cipher mode is one that may be used for
     * encryption / decryption operations via {@link org.owasp.esapi.Encryptor}.
     * @param cipherMode The specified cipher mode to be used for the encryption
     *                   or decryption operation.
     * @return true if the specified cipher mode is in the comma-separated list
     *         of cipher modes supporting both confidentiality and authenticity;
     *         otherwise false.
     * @see #isCombinedCipherMode(String)
     * @see org.owasp.esapi.SecurityConfiguration#getCombinedCipherModes()
     * @see org.owasp.esapi.SecurityConfiguration#getAdditionalAllowedCipherModes()
     */
	public static boolean isAllowedCipherMode(String cipherMode)
	{
	    if ( isCombinedCipherMode(cipherMode) ) {
	        return true;
	    }
	    List<String> extraCipherModes =
	        ESAPI.securityConfiguration().getAdditionalAllowedCipherModes();
	    return extraCipherModes.contains( cipherMode );
	}
	
    /**
     * Check to see if a Message Authentication Code (MAC) is required
     * for a given {@code CipherText} object and the current ESAPI.property
     * settings. A MAC is considered "required" if the specified
     * {@code CipherText} was not encrypted by one of the preferred
     * "combined" cipher modes (e.g., CCM or GCM) and the setting of the
     * current ESAPI properties for the property
     * {@code Encryptor.CipherText.useMAC} is set to {@code true}. (Normally,
     * the setting for {@code Encryptor.CipherText.useMAC} should be set to
     * {@code true} unless FIPS 140-2 compliance is required. See
     * <a href="http://owasp-esapi-java.googlecode.com/svn/trunk/documentation/esapi4java-core-2.0-symmetric-crypto-user-guide.html">
     * User Guide for Symmetric Encryption in ESAPI 2.0</a> and the section
     * on using ESAPI with FIPS for further details.
     *
     * @param ct    The specified {@code CipherText} object to check to see if
     *              it requires a MAC.
     * @returns     True if a MAC is required, false if it is not required.
     */
    public static boolean isMACRequired(CipherText ct) {
        boolean preferredCipherMode =
            CryptoHelper.isCombinedCipherMode( ct.getCipherMode() );
        boolean wantsMAC = ESAPI.securityConfiguration().useMACforCipherText();

        // The preferred "combined" cipher modes such as CCM, GCM, etc. do
        // not require a MAC as a MAC would be superfluous and just require
        // additional computing time.
        return ( !preferredCipherMode && wantsMAC );
    }
	
    /**
     * If a Message Authentication Code (MAC) is required for the specified
     * {@code CipherText} object, then attempt to validate the MAC that
     * should be embedded within the {@code CipherText} object by using a
     * derived key based on the specified {@code SecretKey}.
     *
     * @param sk    The {@code SecretKey} used to derived a key to check
     *              the authenticity via the MAC.
     * @param ct    The {@code CipherText} that we are checking for a
     *              valid MAC. 
     *
     * @return  True is returned if a MAC is required and it is valid as
     *          verified using a key derived from the specified
     *          {@code SecretKey} or a MAC is not required. False is returned
     *          otherwise.
     */
    public static boolean isCipherTextMACvalid(SecretKey sk, CipherText ct)
    {
        if ( CryptoHelper.isMACRequired( ct ) ) {
            try {
                SecretKey authKey = CryptoHelper.computeDerivedKey( sk, ct.getKeySize(), "authenticity");
                boolean validMAC = ct.validateMAC( authKey );
                return validMAC;
            } catch (Exception ex) {
                // Error on side of security. If this fails and can't verify MAC
                // assume it is invalid. Note that CipherText.toString() does not
                // print the actual ciphertext.
                logger.warning(Logger.SECURITY_FAILURE, "Unable to validate MAC for ciphertext " + ct, ex);
                return false;
            }
        }
        return true;
    }
    
	/**
	 * Overwrite a byte array with a specified byte. This is frequently done
	 * to a plaintext byte array so the sensitive data is not lying around
	 * exposed in memory.
	 * @param bytes	The byte array to be overwritten.
	 * @param x The byte array {@code bytes} is overwritten with this byte.
	 */
	public static void overwrite(byte[] bytes, byte x)
	{
		Arrays.fill(bytes, x);
	}
	
	/**
	 * Overwrite a byte array with the byte containing '*'. That is, call
	 * <pre>
	 * 		overwrite(bytes, (byte)'*');
	 * </pre>
	 * @param bytes The byte array to be overwritten.
	 */
	public static void overwrite(byte[] bytes)
	{
		overwrite(bytes, (byte)'*');
	}
	
	// These provide for a bit more type safety when copying bytes around.
	/**
	 * Same as {@code System.arraycopy(src, 0, dest, 0, length)}.
	 * 
     * @param      src      the source array.
     * @param      dest     the destination array.
     * @param      length   the number of array elements to be copied.
     * @exception  IndexOutOfBoundsException  if copying would cause
     *               access of data outside array bounds.
     * @exception  NullPointerException if either <code>src</code> or
     *               <code>dest</code> is <code>null</code>.
	 */
	public static void copyByteArray(final byte[] src, byte[] dest, int length)
	{
		System.arraycopy(src, 0, dest, 0, length);
	}
	
	/**
	 * Same as {@code copyByteArray(src, dest, src.length)}.
     * @param      src      the source array.
     * @param      dest     the destination array.
     * @exception  IndexOutOfBoundsException  if copying would cause
     *               access of data outside array bounds.
     * @exception  NullPointerException if either <code>src</code> or
     *               <code>dest</code> is <code>null</code>.
	 */
	public static void copyByteArray(final byte[] src, byte[] dest)
	{
		copyByteArray(src, dest, src.length);
	}
	
	/**
	 * A "safe" array comparison that is not vulnerable to side-channel
	 * "timing attacks". All comparisons of non-null, equal length bytes should
	 * take same amount of time. We use this for cryptographic comparisons.
	 * 
	 * @param b1   A byte array to compare.
	 * @param b2   A second byte array to compare.
	 * @return     {@code true} if both byte arrays are null or if both byte
	 *             arrays are identical or have the same value; otherwise
	 *             {@code false} is returned.
	 */
	public static boolean arrayCompare(byte[] b1, byte[] b2) {
	    if ( b1 == b2 ) {
	        return true;
	    }
	    if ( b1 == null || b2 == null ) {
	        return (b1 == b2);
	    }
	    if ( b1.length != b2.length ) {
	        return false;
	    }
	    
	    int result = 0;
	    // Make sure to go through ALL the bytes. We use the fact that if
	    // you XOR any bit stream with itself the result will be all 0 bits,
	    // which in turn yields 0 for the result.
	    for(int i = 0; i < b1.length; i++) {
	        // XOR the 2 current bytes and then OR with the outstanding result.
	        result |= (b1[i] ^ b2[i]);
	    }
	    return (result == 0) ? true : false;
	}
	
    /**
     * Calculate the size of a key. The key size is given in bits, but we
     * can only allocate them by octets (i.e., bytes), so make sure we
     * round up to the next whole number of octets to have room for all
     * the bits. For example, a key size of 9 bits would require 2 octets
     * to store it.
     *
     * @param ks    The key size, in bits.
     * @return      The key size, in octets, large enough to accomodate
     *              {@code ks} bits.
     */
    private static int calcKeySize(int ks) {
        assert ks > 0 : "Key size must be > 0 bits.";
        int numBytes = 0;
        int n = ks/8;
        int rem = ks % 8;
        if ( rem == 0 ) {
            numBytes = n;
        } else {
            numBytes = n + 1;
        }
        return numBytes;
    }
  
    /**
     * Prevent public, no-argument CTOR from being auto-generated. Public CTOR
     * is not needed as all methods are static.
     */
    private CryptoHelper() {
    }
}