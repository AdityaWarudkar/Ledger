import Link from "next/link";
import { Receiver } from "@/components/webhooks";
import { PageHeader } from "@/components/ui";
export default function Page() {
  return (
    <>
      <PageHeader
        title="Developer tools"
        description="Integration testing and API diagnostics."
        actions={
          <Link className="button" href="/developers/transfers">
            Transfer requests
          </Link>
        }
      />
      <Receiver />
    </>
  );
}
