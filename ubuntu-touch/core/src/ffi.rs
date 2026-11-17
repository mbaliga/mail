//! C ABI for the Qt shell. Every function returns a heap string of JSON (free it with
//! `mailcore_free`); inputs are NUL-terminated UTF-8. Nothing here logs. Blocking calls
//! (`mailcore_fetch_inbox`) must be run off the UI thread by the caller.

use crate::{client, envelope, otp, providers};
use serde_json::{json, Value};
use std::ffi::{c_char, CStr, CString};

fn arg(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn out(v: Value) -> *mut c_char {
    CString::new(v.to_string().replace('\0', "")).unwrap().into_raw()
}

fn guarded(f: impl FnOnce() -> Value + std::panic::UnwindSafe) -> *mut c_char {
    out(std::panic::catch_unwind(f).unwrap_or_else(|_| json!({"ok": false, "error": "internal error"})))
}

/// # Safety
/// `p` must come from one of this library's functions and be freed exactly once.
#[no_mangle]
pub unsafe extern "C" fn mailcore_free(p: *mut c_char) {
    if !p.is_null() {
        drop(CString::from_raw(p));
    }
}

#[no_mangle]
pub extern "C" fn mailcore_version() -> *mut c_char {
    out(json!({"core": env!("CARGO_PKG_VERSION")}))
}

#[no_mangle]
pub extern "C" fn mailcore_providers() -> *mut c_char {
    guarded(|| json!(providers::ALL.iter().map(|p| p.to_json()).collect::<Vec<_>>()))
}

/// `{"found":bool, "provider":{...}}`
#[no_mangle]
pub extern "C" fn mailcore_provider_for_email(email: *const c_char) -> *mut c_char {
    let email = arg(email);
    guarded(move || match providers::for_email(&email) {
        Some(p) => json!({"found": true, "provider": p.to_json()}),
        None => json!({"found": false}),
    })
}

/// `{"found":bool, "code":..., "isMagicLink":bool, "service":..., "validMinutes":..., "confidence":f}`
#[no_mangle]
pub extern "C" fn mailcore_find_otp(subject: *const c_char, text: *const c_char, from_name: *const c_char) -> *mut c_char {
    let (s, t, f) = (arg(subject), arg(text), arg(from_name));
    guarded(move || match otp::find_otp(&s, &t, if f.is_empty() { None } else { Some(&f) }) {
        Some(o) => json!({"found": true, "code": o.code, "isMagicLink": o.is_magic_link, "service": o.service, "validMinutes": o.valid_minutes, "confidence": o.confidence}),
        None => json!({"found": false}),
    })
}

fn addr_json(a: &envelope::EmailAddress) -> Value {
    json!({"address": a.address, "name": a.name, "display": a.display_name()})
}

/// Blocking. `{"ok":true,"messages":[...]}` or `{"ok":false,"error":"..."}`; the error never contains the password.
#[no_mangle]
pub extern "C" fn mailcore_fetch_inbox(host: *const c_char, port: i32, security: *const c_char, user: *const c_char, password: *const c_char, limit: i32) -> *mut c_char {
    let (host, security, user, password) = (arg(host), arg(security), arg(user), arg(password));
    guarded(move || {
        let port = u16::try_from(port).unwrap_or(0);
        match client::fetch_inbox(&host, port, &security, &user, &password, limit.clamp(1, 200) as i64) {
            Ok(headers) => {
                let msgs: Vec<Value> = headers
                    .iter()
                    .map(|h| {
                        let e = &h.envelope;
                        let from = e.from.first();
                        let otp = otp::find_otp(&e.subject, "", from.map(|f| f.display_name()).as_deref());
                        json!({
                            "uid": h.uid, "seen": h.seen, "subject": e.subject, "date": e.date,
                            "from": from.map(addr_json), "to": e.to.iter().map(addr_json).collect::<Vec<_>>(),
                            "messageId": e.message_id,
                            "otp": otp.and_then(|o| o.code),
                        })
                    })
                    .collect();
                json!({"ok": true, "messages": msgs})
            }
            Err(e) => json!({"ok": false, "error": e.to_string()}),
        }
    })
}
