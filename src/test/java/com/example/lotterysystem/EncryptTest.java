package com.example.lotterysystem;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.symmetric.AES;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

@SpringBootTest
public class EncryptTest {

    // 密码 hash  sha256
    @Test
    void sha256Test() {
        String encrypt =  DigestUtil.sha256Hex("123456789");
        System.out.println("经过sha256 hash 处理后的结果为：" + encrypt);
        //15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225
    }



    // 手机号 对称加密 aes
    @Test
    void aesTest() {
        // 密钥： 16（128） 24（192） 32（256）
        byte[] KET = "123456789abcedfg".getBytes(StandardCharsets.UTF_8);

        // 加密
        AES aes = SecureUtil.aes(KET);
        String encrypt = aes.encryptHex("123456789");
        System.out.println("经过 aes 加密后的结果为：" + encrypt);
        // 5837c858272b4eb353c461ab902a0601

        // 解密
        System.out.println("经过 aes 解密后的结果为：" + aes.decryptStr(encrypt));
        // 123456789

    }


}
