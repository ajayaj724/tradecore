import { signIn } from "@/auth";

export default async function SignInPage({
  searchParams,
}: {
  searchParams: Promise<{ callbackUrl?: string }>;
}) {
  const { callbackUrl } = await searchParams;
  // Same-origin paths only — never redirect off-site after login.
  const target = callbackUrl?.startsWith("/") && !callbackUrl.startsWith("//") ? callbackUrl : "/";

  async function continueWithKeycloak() {
    "use server";
    await signIn("keycloak", { redirectTo: target });
  }

  return (
    <main className="grid min-h-screen place-items-center">
      <div className="w-[360px] rounded-[4px] border border-line bg-panel p-8">
        <p className="text-lg font-extrabold tracking-tight">
          tradecore<span className="text-gold">.</span>
        </p>
        <p className="eyebrow mt-1">Order Management</p>
        <h1 className="mt-7 text-[15px] font-bold">Sign in</h1>
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-ink2">
          Authentication is handled by Keycloak. You will be redirected to the realm&rsquo;s
          login page and returned here.
        </p>
        <form action={continueWithKeycloak} className="mt-5">
          <button
            type="submit"
            className="w-full rounded-[3px] bg-ink py-3 text-sm font-bold text-white hover:opacity-90"
          >
            Continue with Keycloak
          </button>
        </form>
        <p className="mt-5 border-t border-line pt-3.5 text-[11px] leading-relaxed text-ink3">
          Local demo realm — <span className="num">trader1</span> · <span className="num">trader2</span> ·{" "}
          <span className="num">ops1</span>, password <span className="num">demo</span>
        </p>
      </div>
    </main>
  );
}
