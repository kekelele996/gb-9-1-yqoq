import api from './axios'

export const columnApi = {
  list: (params?: { page?: number; size?: number; category?: string }) =>
    api.get('/columns', { params }),

  getById: (id: string) => api.get(`/columns/${id}`),

  getArticles: (columnId: string) => api.get(`/columns/${columnId}/articles`),

  getArticle: (columnId: string, articleId: string) =>
    api.get(`/columns/${columnId}/articles/${articleId}`),

  // 查询当前用户对某专栏的订阅结果（下单/支付结果）
  mySubscription: (columnId: string) =>
    api.get(`/columns/${columnId}/my-subscription`),

  mySubscriptions: () => api.get('/my/subscriptions'),

  create: (data: any) => api.post('/columns', data),

  createArticle: (columnId: string, data: any) =>
    api.post(`/columns/${columnId}/articles`, data),
}
