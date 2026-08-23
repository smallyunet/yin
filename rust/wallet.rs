use k256::SecretKey;
use k256::elliptic_curve::sec1::ToEncodedPoint;
use sha3::{Digest, Keccak256};

pub struct EthWallet {
    pub address: String,
    pub private_key: String,
    pub public_key: String,
}

pub fn eth_wallet_from_private_key(bytes: &[u8; 32]) -> Result<EthWallet, &'static str> {
    let secret = SecretKey::from_slice(bytes).map_err(|_| "invalid secp256k1 private key")?;
    let encoded = secret.public_key().to_encoded_point(false);
    let public_key = encoded.as_bytes();
    let hash = Keccak256::digest(&public_key[1..]);
    let address = checksum_address(&encode_hex(&hash[12..]));
    Ok(EthWallet {
        address,
        private_key: format!("0x{}", encode_hex(bytes)),
        public_key: format!("0x{}", encode_hex(public_key)),
    })
}

fn checksum_address(lowercase: &str) -> String {
    let hash = Keccak256::digest(lowercase.as_bytes());
    let mut result = String::with_capacity(42);
    result.push_str("0x");
    for (index, character) in lowercase.bytes().enumerate() {
        let nibble = if index % 2 == 0 {
            hash[index / 2] >> 4
        } else {
            hash[index / 2] & 0x0f
        };
        if character.is_ascii_alphabetic() && nibble >= 8 {
            result.push((character as char).to_ascii_uppercase());
        } else {
            result.push(character as char);
        }
    }
    result
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        result.push(HEX[(byte >> 4) as usize] as char);
        result.push(HEX[(byte & 0x0f) as usize] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_the_known_ethereum_address_for_private_key_one() {
        let mut private_key = [0_u8; 32];
        private_key[31] = 1;
        let wallet = eth_wallet_from_private_key(&private_key).unwrap();
        assert_eq!(wallet.address, "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf");
        assert_eq!(wallet.private_key.len(), 66);
        assert_eq!(wallet.public_key.len(), 132);
        assert!(wallet.public_key.starts_with("0x04"));
    }

    #[test]
    fn rejects_zero_as_a_private_key() {
        assert!(eth_wallet_from_private_key(&[0_u8; 32]).is_err());
    }
}
