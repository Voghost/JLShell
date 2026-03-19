package com.jlshell.data.crypto;

import javax.crypto.SecretKey;

/**
 * AES 主密钥加载抽象。
 */
public interface MasterKeyProvider {

    SecretKey loadOrCreate();
}
