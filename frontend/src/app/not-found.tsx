import Link from "next/link";
export default function NotFound() {
  return (
    <div className="empty">
      <h1>Page not found</h1>
      <p>The requested page does not exist.</p>
      <Link className="button button-primary" href="/accounts">
        Go to accounts
      </Link>
    </div>
  );
}
