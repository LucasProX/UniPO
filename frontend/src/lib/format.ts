import dayjs from 'dayjs';

export function formatDateTime(value?: string) {
  if (!value) return '暂无截止';
  return dayjs(value).format('M月D日 HH:mm');
}

export function daysLeft(value?: string) {
  if (!value) return '无截止';
  const diff = dayjs(value).diff(dayjs(), 'hour');
  if (diff < 0) return '已错过';
  if (diff < 24) return `${diff} 小时内`;
  return `${Math.ceil(diff / 24)} 天后`;
}

export function importanceText(value?: string) {
  if (value === 'high') return '高';
  if (value === 'low') return '低';
  return '中';
}
