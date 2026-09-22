"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

const links = [
  {
    href: "/accounts",
    title: "Accounts",
    path: "M4 6h16M4 12h16M4 18h16M8 3v18",
  },
  {
    href: "/transfers/new",
    title: "New transfer",
    path: "M4 8h16m-5-5 5 5-5 5M20 16H4m5-5-5 5 5 5",
  },
  {
    href: "/transfers",
    title: "Transfers",
    path: "M5 3h14v18H5zM9 8h6M9 12h6M9 16h4",
  },
  {
    href: "/webhooks",
    title: "Webhooks",
    path: "M8 4v9a5 5 0 0 0 10 0M4 4h8M14 13h8M3 20h7",
  },
  { href: "/system", title: "Reconciliation", path: "M3 12h4l3-7 4 14 3-7h4" },
  {
    href: "/developers",
    title: "Developer tools",
    path: "m8 7-5 5 5 5m8-10 5 5-5 5m-3-14-2 18",
  },
];
export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const active = links.find((link) =>
    link.href === "/transfers/new"
      ? path === link.href
      : path.startsWith(link.href) &&
        !(link.href === "/transfers" && path === "/transfers/new"),
  );
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className={`sidebar ${menuOpen ? "sidebar-open" : ""}`}>
        <Link
          className="brand"
          href="/accounts"
          onClick={() => setMenuOpen(false)}
        >
          <svg width="28" height="28" viewBox="0 0 28 28" aria-hidden="true">
            <rect
              x="1"
              y="1"
              width="26"
              height="26"
              rx="4"
              fill="currentColor"
            />
            <path
              d="M8 7v14h13M13 7v9h8"
              stroke="white"
              strokeWidth="2"
              fill="none"
            />
          </svg>
          <span>
            Ledger<span className="brand-sub">Account operations</span>
          </span>
        </Link>
        <button
          className="mobile-menu button"
          aria-expanded={menuOpen}
          aria-controls="main-nav"
          onClick={() => setMenuOpen(!menuOpen)}
        >
          Menu
        </button>
        <div className="nav-area" id="main-nav">
          <p className="nav-label">Payments</p>
          <nav aria-label="Main navigation">
            {links.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                onClick={() => setMenuOpen(false)}
                aria-current={active?.href === link.href ? "page" : undefined}
                className={
                  active?.href === link.href ? "nav-link active" : "nav-link"
                }
              >
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.4"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d={link.path} />
                </svg>
                {link.title}
              </Link>
            ))}
          </nav>
        </div>
        <div className="sidebar-footer">
          <div>
            <strong>
              {process.env.NEXT_PUBLIC_WORKSPACE_LABEL || "Ledger"}
            </strong>
            <p>Operations workspace</p>
          </div>
        </div>
      </aside>
      <div className="workspace">
        <div className="topbar">
          <span>
            Ledger <span className="slash">/</span>{" "}
            <strong>{active?.title || "Ledger"}</strong>
          </span>
        </div>
        <main id="main" tabIndex={-1}>
          {children}
        </main>
      </div>
    </div>
  );
}
