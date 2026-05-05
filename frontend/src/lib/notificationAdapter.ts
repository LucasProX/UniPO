export const notificationAdapter = {
  async requestPermission() {
    if (!('Notification' in window)) {
      return 'unsupported';
    }
    return Notification.requestPermission();
  },
  async notify(title: string, body: string) {
    if (!('Notification' in window) || Notification.permission !== 'granted') {
      return false;
    }
    new Notification(title, { body });
    return true;
  }
};
