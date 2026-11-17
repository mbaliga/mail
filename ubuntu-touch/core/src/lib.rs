//! mailcore: portable core of the Ubuntu Touch port of "You've got Mail".
//! Pure logic first (MIME, IMAP grammar, ENVELOPE, OTP, providers); the TLS IMAP client is thin.
//! `ffi` exposes a JSON-over-C API to the Qt/QML shell in `../cpp`.

pub mod client;
pub mod envelope;
pub mod ffi;
pub mod imap;
pub mod mime;
pub mod otp;
pub mod providers;
