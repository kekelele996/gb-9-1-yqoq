import api from './axios'

export const orderApi = {
  list: (params?: { page?: number; size?: number }) =>
    api.get('/orders', { params }),

  getById: (id: string) => api.get(`/orders/${id}`),

  // 选择月付/季付/年付后生成待支付订单
  create: (data: { type: string; itemId: string; plan?: string }) =>
    api.post('/orders', data),

  // 支付订单（同一订单重复支付只生效一次）
  pay: (orderId: string) => api.post(`/orders/${orderId}/pay`),

  // 取消待支付订单（已取消订单不可支付）
  cancel: (orderId: string) => api.post(`/orders/${orderId}/cancel`),

  requestInvoice: (orderId: string, data: any) =>
    api.post(`/orders/${orderId}/invoice`, data),
}
