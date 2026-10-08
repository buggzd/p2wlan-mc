use std::fmt;

use serde::de::{DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde_json::value::RawValue;
use serde_json::{Map, Value};

use crate::{ProtocolError, MAX_DEPTH, MAX_FRAME_BYTES, MAX_ITEMS, MAX_NODES};

/// Validate numbers lexically before numeric conversion: bounded token and
/// BigDecimal-compatible scale. No floating-point conversion loses an IPC ID.
fn number(raw: &str) -> Result<(), ProtocolError> {
    if raw.len() > 64 {
        return Err(ProtocolError::Capacity);
    }
    let (mantissa, exponent) = raw.split_once(['e', 'E']).unwrap_or((raw, "0"));
    let exponent: i64 = exponent.parse().map_err(|_| ProtocolError::Capacity)?;
    let fractional = mantissa
        .split_once('.')
        .map_or(0, |(_, fraction)| fraction.len()) as i64;
    let scale = fractional
        .checked_sub(exponent)
        .ok_or(ProtocolError::Capacity)?;
    if !(-128..=128).contains(&scale) {
        return Err(ProtocolError::Capacity);
    }
    Ok(())
}

fn string(value: &str) -> Result<(), ProtocolError> {
    if value.encode_utf16().count() > MAX_FRAME_BYTES / 2 {
        return Err(ProtocolError::Capacity);
    }
    Ok(())
}

// serde_json rejects unpaired Unicode escapes. Classify that failure without
// matching error messages (which can contain credentials or user input).
fn unicode_escapes(raw: &str) -> Result<(), ProtocolError> {
    let bytes = raw.as_bytes();
    let mut offset = 1;
    while offset + 1 < bytes.len() {
        if bytes[offset] != b'\\' {
            offset += 1;
            continue;
        }
        if bytes.get(offset + 1) != Some(&b'u') {
            offset += 2;
            continue;
        }
        let read = |at: usize| -> Option<u16> {
            let token = std::str::from_utf8(bytes.get(at + 2..at + 6)?).ok()?;
            u16::from_str_radix(token, 16).ok()
        };
        let Some(code) = read(offset) else {
            return Err(ProtocolError::InvalidJson);
        };
        if (0xd800..=0xdbff).contains(&code) {
            let next = offset + 6;
            if bytes.get(next..next + 2) != Some(b"\\u")
                || !read(next).is_some_and(|code| (0xdc00..=0xdfff).contains(&code))
            {
                return Err(ProtocolError::InvalidUtf8);
            }
            offset += 12;
        } else if (0xdc00..=0xdfff).contains(&code) {
            return Err(ProtocolError::InvalidUtf8);
        } else {
            offset += 6;
        }
    }
    Ok(())
}

fn check_string_tokens(text: &str) -> Result<(), ProtocolError> {
    let bytes = text.as_bytes();
    let mut offset = 0;
    while offset < bytes.len() {
        if bytes[offset] != b'"' {
            offset += 1;
            continue;
        }
        let start = offset;
        offset += 1;
        while offset < bytes.len() && bytes[offset] != b'"' {
            offset += if bytes[offset] == b'\\' { 2 } else { 1 };
        }
        if offset >= bytes.len() {
            return Err(ProtocolError::InvalidJson);
        }
        offset += 1;
        unicode_escapes(&text[start..offset])?;
    }
    Ok(())
}

#[derive(Default)]
struct Budget {
    nodes: usize,
    failure: Option<ProtocolError>,
}

impl Budget {
    fn node(&mut self, depth: usize) -> Result<(), ProtocolError> {
        if depth > MAX_DEPTH {
            return Err(ProtocolError::Depth);
        }
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            return Err(ProtocolError::Capacity);
        }
        Ok(())
    }

    fn value(&mut self, raw: &str, depth: usize) -> Result<Value, ProtocolError> {
        self.node(depth)?;
        match raw.as_bytes().first() {
            Some(b'{' | b'[') => {
                let mut deserializer = serde_json::Deserializer::from_str(raw);
                let result = Container {
                    budget: self,
                    depth,
                }
                .deserialize(&mut deserializer);
                result.map_err(|_| self.failure.take().unwrap_or(ProtocolError::InvalidJson))
            }
            Some(b'"') => {
                unicode_escapes(raw)?;
                let value: String =
                    serde_json::from_str(raw).map_err(|_| ProtocolError::InvalidJson)?;
                string(&value)?;
                Ok(Value::String(value))
            }
            _ => {
                if !matches!(raw, "null" | "true" | "false") {
                    number(raw)?;
                }
                serde_json::from_str(raw).map_err(|_| ProtocolError::InvalidJson)
            }
        }
    }
}

struct Container<'a> {
    budget: &'a mut Budget,
    depth: usize,
}

impl<'de> DeserializeSeed<'de> for Container<'_> {
    type Value = Value;

    fn deserialize<D: serde::Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        deserializer.deserialize_any(self)
    }
}

impl Container<'_> {
    fn fail<E: serde::de::Error>(&mut self, error: ProtocolError) -> E {
        self.budget.failure = Some(error);
        E::custom("invalid_ipc_value")
    }
    fn child<E: serde::de::Error>(&mut self, raw: &RawValue) -> Result<Value, E> {
        self.budget
            .value(raw.get(), self.depth + 1)
            .map_err(|error| self.fail(error))
    }
}

impl<'de> Visitor<'de> for Container<'_> {
    type Value = Value;

    fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        formatter.write_str("bounded IPC container")
    }

    fn visit_map<A: MapAccess<'de>>(mut self, mut access: A) -> Result<Value, A::Error> {
        let mut object = Map::new();
        // The lexical pass validated Unicode escapes, including object keys.
        while let Some(key) = access.next_key::<String>()? {
            if object.len() >= MAX_ITEMS {
                return Err(self.fail(ProtocolError::Capacity));
            }
            string(&key).map_err(|error| self.fail(error))?;
            if object.contains_key(&key) {
                return Err(self.fail(ProtocolError::InvalidJson));
            }
            let raw: &RawValue = access.next_value()?;
            object.insert(key, self.child(raw)?);
        }
        Ok(Value::Object(object))
    }

    fn visit_seq<A: SeqAccess<'de>>(mut self, mut access: A) -> Result<Value, A::Error> {
        let mut array = Vec::new();
        while let Some(raw) = access.next_element::<&RawValue>()? {
            if array.len() >= MAX_ITEMS {
                return Err(self.fail(ProtocolError::Capacity));
            }
            array.push(self.child(raw)?);
        }
        Ok(Value::Array(array))
    }
}

pub fn parse_object(bytes: &[u8]) -> Result<Value, ProtocolError> {
    if bytes.is_empty() || bytes.len() > MAX_FRAME_BYTES {
        return Err(ProtocolError::FrameSize);
    }
    let text = std::str::from_utf8(bytes).map_err(|_| ProtocolError::InvalidUtf8)?;
    check_string_tokens(text)?;
    let raw: &RawValue = serde_json::from_str(text).map_err(|_| ProtocolError::InvalidJson)?;
    let object = Budget::default().value(raw.get(), 0)?;
    if !object.is_object() {
        return Err(ProtocolError::InvalidJson);
    }
    Ok(object)
}

pub(crate) fn validate_value(value: &Value) -> Result<(), ProtocolError> {
    fn walk(
        value: &Value,
        depth: usize,
        budget: &mut Budget,
        size: &mut usize,
    ) -> Result<(), ProtocolError> {
        budget.node(depth)?;
        let mut count = |text: &str| -> Result<(), ProtocolError> {
            string(text)?;
            *size += text.len();
            if *size > MAX_FRAME_BYTES {
                return Err(ProtocolError::Capacity);
            }
            Ok(())
        };
        match value {
            Value::Object(object) => {
                if object.len() > MAX_ITEMS {
                    return Err(ProtocolError::Capacity);
                }
                for key in object.keys() {
                    count(key)?;
                }
                for child in object.values() {
                    walk(child, depth + 1, budget, size)?;
                }
            }
            Value::Array(array) => {
                if array.len() > MAX_ITEMS {
                    return Err(ProtocolError::Capacity);
                }
                for child in array {
                    walk(child, depth + 1, budget, size)?;
                }
            }
            Value::String(text) => count(text)?,
            Value::Number(value) => {
                let token = value.to_string();
                number(&token)?;
                count(&token)?;
            }
            _ => {}
        }
        Ok(())
    }
    if !value.is_object() {
        return Err(ProtocolError::InvalidJson);
    }
    walk(value, 0, &mut Budget::default(), &mut 0)
}
