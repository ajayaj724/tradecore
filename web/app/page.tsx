import { redirect } from "next/navigation";
import { auth, signOut } from "@/auth";
import { TradingScreen } from "@/components/TradingScreen";

export default async function Page() {
  const session = await auth();
  // The proxy already gates this route; this is the server-side backstop.
  if (!session?.accessToken) redirect("/signin");

  async function signOutAction() {
    "use server";
    await signOut({ redirectTo: "/signin" });
  }

  return (
    <TradingScreen
      username={session.username ?? session.user?.name ?? "trader"}
      roles={session.roles ?? []}
      signOutAction={signOutAction}
    />
  );
}
