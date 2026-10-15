import { createRoot } from "react-dom/client";
import { ServerStore } from "./store/serverStore";
import { App } from "./ui/App";
import { notifications } from "./ui/notifications";
import { settings } from "./ui/settings";
import "./ui/styles.css";

settings.apply();
settings.watchSystem();

const store = new ServerStore();
notifications.attach(store);

createRoot(document.getElementById("root")!).render(<App store={store} />);
